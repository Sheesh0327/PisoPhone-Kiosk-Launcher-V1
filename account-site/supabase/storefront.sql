begin;

create table public.store_products (
    sku text primary key,
    edition text not null check (edition in ('hub', 'wifi', 'phone')),
    format text not null check (format in ('kit', 'assembled')),
    name text not null,
    contents text not null,
    price integer not null check (price between 1 and 10000000),
    phone_price integer check (phone_price between 1 and 10000000),
    lead_days integer not null check (lead_days between 1 and 365),
    published boolean not null default true,
    unique (edition, format),
    check (phone_price is null or (format = 'assembled' and edition in ('hub', 'phone')))
);

create table public.store_sellers (
    user_id uuid primary key references auth.users (id) on delete cascade
);

create table public.store_orders (
    id uuid primary key default gen_random_uuid(),
    created_at timestamptz not null default now(),
    created_by uuid not null,
    buyer_id uuid,
    buyer_name text not null,
    source text not null check (source in ('web', 'direct')),
    request_id uuid not null,
    delivery text not null check (delivery in ('pickup', 'shipping')),
    address text,
    status text not null check (status in ('needs_quote', 'awaiting_payment', 'payment_review', 'paid')),
    subtotal bigint not null check (subtotal > 0),
    shipping integer check (shipping between 0 and 10000000),
    total bigint generated always as (subtotal + shipping) stored,
    payment_method text check (payment_method in ('gcash', 'maya', 'bank')),
    payment_reference text,
    paid_at timestamptz,
    unique (created_by, request_id),
    check ((source = 'web' and buyer_id = created_by and buyer_id is not null)
        or (source = 'direct' and buyer_id is null)),
    check ((delivery = 'shipping' and address is not null) or (delivery = 'pickup' and address is null)),
    check ((status = 'needs_quote') = (shipping is null)),
    check ((status in ('payment_review', 'paid')) = (payment_method is not null and payment_reference is not null)),
    check ((status = 'paid') = (paid_at is not null))
);

create unique index store_unique_payment on public.store_orders
    (payment_method, upper(payment_reference)) where payment_reference is not null;
create index store_orders_by_buyer on public.store_orders (buyer_id, created_at desc);

create table public.store_order_lines (
    order_id uuid not null references public.store_orders (id),
    sku text not null,
    name text not null,
    quantity integer not null check (quantity between 1 and 10),
    price integer not null check (price > 0),
    phone_quantity integer not null check (phone_quantity between 0 and quantity),
    phone_price integer not null check (phone_price >= 0),
    lead_days integer not null,
    primary key (order_id, sku)
);

create table public.store_events (
    id bigint generated always as identity primary key,
    order_id uuid references public.store_orders (id),
    actor uuid not null,
    action text not null,
    created_at timestamptz not null default now()
);

create table public.store_quote_notices (
    order_id uuid primary key references public.store_orders (id),
    status text not null default 'pending' check (status in ('pending', 'sending', 'sent', 'failed')),
    attempts integer not null default 0,
    lease_id uuid,
    lease_until timestamptz,
    sent_at timestamptz
);

alter table public.store_products enable row level security;
alter table public.store_sellers enable row level security;
alter table public.store_orders enable row level security;
alter table public.store_order_lines enable row level security;
alter table public.store_events enable row level security;
alter table public.store_quote_notices enable row level security;

revoke all on public.store_products, public.store_sellers, public.store_orders,
    public.store_order_lines, public.store_events, public.store_quote_notices from public, anon, authenticated;
grant select on public.store_products to anon, authenticated;
grant select on public.store_orders, public.store_order_lines, public.store_quote_notices to authenticated;

create function public.store_is_seller() returns boolean
language sql security definer stable set search_path = ''
as $$
    select exists (select from public.store_sellers where user_id = (select auth.uid()));
$$;

revoke all on function public.store_is_seller() from public, anon, authenticated;
grant execute on function public.store_is_seller() to authenticated;

create policy store_products_public on public.store_products for select to anon using (published);
create policy store_products_accounts on public.store_products for select to authenticated
    using (published or (select public.store_is_seller()));
create policy store_orders_visible on public.store_orders for select to authenticated
    using (buyer_id = (select auth.uid()) or (select public.store_is_seller()));
create policy store_lines_visible on public.store_order_lines for select to authenticated
    using (exists (select from public.store_orders where id = order_id));
create policy store_notices_seller on public.store_quote_notices for select to authenticated
    using ((select public.store_is_seller()));

create function public.store_place_order(
    p_request_id uuid, p_items jsonb, p_delivery text, p_name text, p_address text, p_direct boolean
) returns uuid language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    new_id uuid;
    item record;
    product public.store_products%rowtype;
    amount bigint := 0;
    lines jsonb := '[]';
begin
    if actor is null or (p_direct is true and not public.store_is_seller()) then
        raise insufficient_privilege;
    end if;
    if p_request_id is null or p_direct is null or p_delivery is null or p_delivery not in ('pickup','shipping')
        or coalesce(length(trim(p_name)), 0) not between 1 and 120
        or (p_delivery = 'shipping' and coalesce(length(trim(p_address)), 0) not between 1 and 600)
        or jsonb_typeof(p_items) is distinct from 'array' then
        raise exception 'Check the order details.' using errcode = '22023';
    end if;
    if jsonb_array_length(p_items) not between 1 and 6 then
        raise exception 'Select at least one product.' using errcode = '22023';
    end if;
    select id into new_id from public.store_orders where created_by = actor and request_id = p_request_id;
    if found then return new_id; end if;
    for item in select * from jsonb_to_recordset(p_items) as i(sku text, quantity integer, phone_quantity integer)
    loop
        select * into product from public.store_products where sku = item.sku and published for share;
        if not found or coalesce(item.quantity, 0) not between 1 and 10
            or coalesce(item.phone_quantity, -1) not between 0 and item.quantity
            or (item.phone_quantity > 0 and product.phone_price is null)
            or lines @> jsonb_build_array(jsonb_build_object('sku', item.sku)) then
            raise exception 'The selected product or add-on is invalid.' using errcode = '22023';
        end if;
        amount := amount + product.price::bigint * item.quantity
            + coalesce(product.phone_price, 0)::bigint * item.phone_quantity;
        lines := lines || jsonb_build_array(jsonb_build_object(
            'sku', product.sku, 'name', product.name, 'price', product.price, 'quantity', item.quantity,
            'phone_price', coalesce(product.phone_price, 0), 'phone_quantity', item.phone_quantity,
            'lead_days', product.lead_days));
    end loop;
    insert into public.store_orders (created_by, buyer_id, buyer_name, request_id, source, delivery, address, status, subtotal, shipping)
    values (actor, case when not p_direct then actor end, trim(p_name), p_request_id, case when p_direct then 'direct' else 'web' end,
        p_delivery, case when p_delivery = 'shipping' then trim(p_address) end,
        case when p_delivery = 'shipping' then 'needs_quote' else 'awaiting_payment' end,
        amount, case when p_delivery = 'pickup' then 0 end)
    on conflict (created_by, request_id) do nothing returning id into new_id;
    if new_id is null then
        select id into strict new_id from public.store_orders where created_by = actor and request_id = p_request_id;
        return new_id;
    end if;
    insert into public.store_order_lines
    select new_id, l.* from jsonb_to_recordset(lines)
    as l(sku text, name text, quantity integer, price integer, phone_quantity integer, phone_price integer, lead_days integer);
    insert into public.store_events (order_id, actor, action) values (new_id, actor, 'placed');
    return new_id;
end;
$$;

create function public.store_seller_quote(p_order_id uuid, p_shipping integer) returns void
language plpgsql security definer set search_path = ''
as $$
declare customer uuid;
begin
    if not public.store_is_seller() then raise insufficient_privilege; end if;
    if coalesce(p_shipping, -1) not between 0 and 10000000 then
        raise exception 'Enter a valid shipping amount.' using errcode = '22023';
    end if;
    update public.store_orders set shipping = p_shipping, status = 'awaiting_payment'
        where id = p_order_id and status = 'needs_quote' returning buyer_id into customer;
    if not found then raise exception 'This order no longer needs a quote.' using errcode = '22023'; end if;
    if customer is not null then insert into public.store_quote_notices (order_id) values (p_order_id); end if;
    insert into public.store_events (order_id, actor, action) values (p_order_id, auth.uid(), 'quoted');
end;
$$;

create function public.store_submit_payment(p_order_id uuid, p_method text, p_reference text) returns void
language plpgsql security definer set search_path = ''
as $$
declare actor uuid := auth.uid();
    ref text := upper(trim(p_reference));
begin
    if actor is null then raise insufficient_privilege; end if;
    if p_method is null or p_method not in ('gcash','maya','bank') or ref is null or ref !~ '^[A-Z0-9-]{4,80}$' then
        raise exception 'Enter a valid test payment reference.' using errcode = '22023';
    end if;
    update public.store_orders set status = 'payment_review', payment_method = p_method, payment_reference = ref
        where id = p_order_id and status = 'awaiting_payment'
            and (buyer_id = actor or (source = 'direct' and public.store_is_seller()));
    if found then
        insert into public.store_events (order_id, actor, action) values (p_order_id, actor, 'reference_submitted');
        return;
    end if;
    if exists (select from public.store_orders where id = p_order_id and status in ('payment_review', 'paid')
        and payment_method = p_method and payment_reference = ref
        and (buyer_id = actor or (source = 'direct' and public.store_is_seller()))) then return; end if;
    raise exception 'This order cannot accept a payment reference.' using errcode = '22023';
exception when unique_violation then
    raise exception 'This reference could not be recorded. Contact the seller for review.' using errcode = '22023';
end;
$$;

create function public.store_seller_verify(p_order_id uuid, p_confirmed_amount bigint) returns void
language plpgsql security definer set search_path = ''
as $$
begin
    if not public.store_is_seller() then raise insufficient_privilege; end if;
    update public.store_orders set status = 'paid', paid_at = now()
        where id = p_order_id and status = 'payment_review' and total = p_confirmed_amount;
    if not found then raise exception 'The confirmed amount or order status does not match.' using errcode = '22023'; end if;
    insert into public.store_events (order_id, actor, action) values (p_order_id, auth.uid(), 'payment_verified');
end;
$$;

create function public.store_seller_product(
    p_sku text, p_price integer, p_phone_price integer, p_lead_days integer, p_contents text, p_published boolean
) returns void language plpgsql security definer set search_path = ''
as $$
begin
    if not public.store_is_seller() then raise insufficient_privilege; end if;
    if coalesce(length(trim(p_contents)),0) not between 1 and 500 then
        raise exception 'Enter the kit contents.' using errcode = '22023';
    end if;
    update public.store_products set price = p_price, phone_price = p_phone_price,
        lead_days = p_lead_days, contents = trim(p_contents), published = p_published where sku = p_sku;
    if not found then raise exception 'Product not found.' using errcode = '22023'; end if;
    insert into public.store_events (actor, action) values (auth.uid(), 'catalog_updated:' || p_sku);
end;
$$;

create function public.store_seller_claim_notice(p_order_id uuid)
returns table (recipient text, total bigint, lease uuid)
language plpgsql security definer set search_path = ''
as $$
declare claim uuid := gen_random_uuid();
begin
    if not public.store_is_seller() then raise insufficient_privilege; end if;
    return query
        update public.store_quote_notices n set status = 'sending', attempts = attempts + 1,
            lease_id = claim, lease_until = now() + interval '2 minutes'
        from public.store_orders o join auth.users u on u.id = o.buyer_id
        where n.order_id = p_order_id and o.id = n.order_id and u.email is not null
            and (n.status in ('pending','failed') or (n.status = 'sending' and n.lease_until < now()))
        returning u.email::text, o.total, claim;
end;
$$;

create function public.store_seller_finish_notice(p_order_id uuid, p_lease uuid, p_sent boolean)
returns void language plpgsql security definer set search_path = ''
as $$
begin
    if not public.store_is_seller() then raise insufficient_privilege; end if;
    update public.store_quote_notices set status = case when p_sent then 'sent' else 'failed' end,
        sent_at = case when p_sent then now() end, lease_id = null, lease_until = null
        where order_id = p_order_id and status = 'sending' and lease_id = p_lease;
end;
$$;

revoke all on function
    public.store_place_order(uuid,jsonb,text,text,text,boolean), public.store_seller_quote(uuid,integer),
    public.store_submit_payment(uuid,text,text), public.store_seller_verify(uuid,bigint),
    public.store_seller_product(text,integer,integer,integer,text,boolean),
    public.store_seller_claim_notice(uuid), public.store_seller_finish_notice(uuid,uuid,boolean)
    from public, anon, authenticated;
grant execute on function
    public.store_place_order(uuid,jsonb,text,text,text,boolean), public.store_seller_quote(uuid,integer),
    public.store_submit_payment(uuid,text,text), public.store_seller_verify(uuid,bigint),
    public.store_seller_product(text,integer,integer,integer,text,boolean),
    public.store_seller_claim_notice(uuid), public.store_seller_finish_notice(uuid,uuid,boolean)
    to authenticated;

insert into public.store_products (sku, edition, format, name, contents, price, phone_price, lead_days) values
    ('hub-kit','hub','kit','Full TechNet Hub','Prepared router and coin box for DIY assembly.',650000,null,5),
    ('hub-system','hub','assembled','Full TechNet Hub','Assembled router and coin box. Rental phone optional.',850000,350000,7),
    ('wifi-kit','wifi','kit','PisoWiFi only','Prepared router and coin box for DIY assembly.',450000,null,4),
    ('wifi-system','wifi','assembled','PisoWiFi only','Assembled router and coin box for paid Wi-Fi.',600000,null,6),
    ('phone-kit','phone','kit','PisoPhone only','Prepared router and coin box for DIY assembly.',420000,null,4),
    ('phone-system','phone','assembled','PisoPhone only','Assembled router and coin box. Rental phone optional.',570000,350000,6);

commit;
