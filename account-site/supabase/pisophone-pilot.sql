-- Beta Supabase project only. Apply after storefront.sql.
begin;

create schema if not exists piso_pilot;
revoke all on schema piso_pilot from public, anon, authenticated;

create table piso_pilot.service_credential (
    singleton boolean primary key default true check (singleton),
    secret_sha text not null check (secret_sha ~ '^[0-9a-f]{64}$')
);
alter table piso_pilot.service_credential enable row level security;

create table piso_pilot.kit (
    id uuid primary key default gen_random_uuid(),
    workshop_user uuid not null,
    claim_sha text not null unique check (claim_sha ~ '^[0-9a-f]{64}$'),
    owner_user uuid,
    slot_limit integer not null check (slot_limit between 2 and 12),
    created_at timestamptz not null default now(),
    claimed_at timestamptz,
    check ((owner_user is null) = (claimed_at is null))
);

create table piso_pilot.board (
    kit_id uuid not null references piso_pilot.kit(id),
    revision integer not null check (revision > 0),
    mac text not null unique check (mac ~ '^[0-9a-f]{12}$' and mac <> '000000000000'),
    device_sha text not null unique check (device_sha ~ '^[0-9a-f]{64}$'),
    state text not null default 'assigned' check (state in ('assigned', 'active', 'retired')),
    activation_requested_at timestamptz,
    activated_at timestamptz,
    retired_at timestamptz,
    primary key (kit_id, revision),
    check ((state = 'retired') = (retired_at is not null)),
    check ((state = 'assigned') = (activated_at is null))
);
create unique index pilot_one_assigned on piso_pilot.board (kit_id) where state = 'assigned';
create unique index pilot_one_active on piso_pilot.board (kit_id) where state = 'active';
create index pilot_kits_owner on piso_pilot.kit (owner_user);
alter table piso_pilot.kit enable row level security;
alter table piso_pilot.board enable row level security;

create function piso_pilot.require_workshop() returns uuid
language plpgsql stable security invoker set search_path = ''
as $$
declare
    actor uuid := auth.uid();
begin
    if actor is null or not public.store_is_seller() then
        raise exception 'Workshop access required' using errcode = '42501';
    end if;
    return actor;
end;
$$;

-- The workshop retains the randomly generated claim code and board credential until both
-- the handover card and board are prepared. Only their SHA-256 digests enter this RPC.
create function public.pilot_register_kit(p_claim_sha text, p_mac text, p_device_sha text, p_slot_limit integer)
returns uuid language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := piso_pilot.require_workshop();
    unit piso_pilot.kit;
begin
    insert into piso_pilot.kit (workshop_user, claim_sha, slot_limit)
        values (actor, p_claim_sha, p_slot_limit)
        on conflict (claim_sha) do nothing returning * into unit;

    if found then
        insert into piso_pilot.board (kit_id, revision, mac, device_sha)
            values (unit.id, 1, p_mac, p_device_sha);
        return unit.id;
    end if;

    select k.* into unit from piso_pilot.kit k
        where k.claim_sha = p_claim_sha and k.workshop_user = actor and k.slot_limit = p_slot_limit;
    if unit.id is not null and exists (
        select 1 from piso_pilot.board b
        where b.kit_id = unit.id and b.revision = 1 and b.mac = p_mac and b.device_sha = p_device_sha
    ) then
        return unit.id;
    end if;
    raise exception 'Kit registration conflicts with an existing record' using errcode = '23505';
end;
$$;

create function public.pilot_claim_kit(p_claim_code text)
returns uuid language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    code text := lower(btrim(p_claim_code));
    unit piso_pilot.kit;
begin
    if actor is null then
        raise exception 'Sign in to claim a kit' using errcode = '42501';
    end if;
    if code is null or code !~ '^[0-9a-f]{32}$' then return null; end if;

    select k.* into unit from piso_pilot.kit k
        where k.claim_sha = encode(sha256(convert_to(code, 'UTF8')), 'hex') for update;
    if unit.id is null then return null; end if;
    if unit.owner_user is not null then
        if unit.owner_user = actor then return unit.id; end if;
        return null;
    end if;

    update piso_pilot.kit set owner_user = actor, claimed_at = now() where id = unit.id;
    update piso_pilot.board set activation_requested_at = now()
        where kit_id = unit.id and revision = 1;
    return unit.id;
end;
$$;

create function public.pilot_my_kits()
returns table (
    id uuid, slot_limit integer, active_mac text, active_revision integer,
    assigned_mac text, assigned_revision integer, activation_requested boolean
)
language sql stable security definer set search_path = ''
as $$
    select k.id, k.slot_limit, a.mac, a.revision, n.mac, n.revision, n.activation_requested_at is not null
    from piso_pilot.kit k
    left join piso_pilot.board a on a.kit_id = k.id and a.state = 'active'
    left join piso_pilot.board n on n.kit_id = k.id and n.state = 'assigned'
    where k.owner_user = auth.uid()
    order by k.claimed_at desc, k.id;
$$;

create function public.pilot_assign_replacement(p_kit uuid, p_mac text, p_device_sha text)
returns integer language plpgsql security definer set search_path = ''
as $$
declare
    unit piso_pilot.kit;
    existing piso_pilot.board;
    next_revision integer;
begin
    perform piso_pilot.require_workshop();
    select k.* into unit from piso_pilot.kit k where k.id = p_kit for update;
    if unit.id is null or unit.owner_user is null then
        raise exception 'Claimed kit required' using errcode = '22023';
    end if;

    -- Retry with the original workshop inputs, including after the board has activated.
    select b.* into existing from piso_pilot.board b where b.mac = p_mac;
    if found then
        if existing.kit_id = p_kit and existing.revision > 1 and existing.device_sha = p_device_sha then
            return existing.revision;
        end if;
        raise exception 'Board is already registered' using errcode = '23505';
    end if;

    if exists (select 1 from piso_pilot.board where kit_id = p_kit and state = 'assigned') then
        raise exception 'This kit already has an assigned board waiting for activation' using errcode = '23505';
    end if;

    select max(revision) + 1 into next_revision from piso_pilot.board where kit_id = p_kit;
    insert into piso_pilot.board (kit_id, revision, mac, device_sha)
        values (p_kit, next_revision, p_mac, p_device_sha);
    return next_revision;
end;
$$;

create function public.pilot_request_replacement(p_kit uuid)
returns boolean language plpgsql security definer set search_path = ''
as $$
declare
    unit uuid;
begin
    select k.id into unit from piso_pilot.kit k where k.id = p_kit and k.owner_user = auth.uid() for update;
    if unit is null then return false; end if;

    update piso_pilot.board set activation_requested_at = coalesce(activation_requested_at, now())
        where kit_id = unit and state = 'assigned' and revision > 1;
    return found;
end;
$$;

-- Only the beta licensing service calls this RPC. It authenticates by the provisioned
-- board credential and confirms a verified grant acknowledgement before supplying a saved revision.
create function public.pilot_board_check_in(p_worker_secret text, p_device_sha text, p_saved_revision integer default null)
returns table (kit_id uuid, mac text, revision integer, slot_limit integer, status text)
language plpgsql security definer set search_path = ''
as $$
declare
    device piso_pilot.board;
    unit piso_pilot.kit;
begin
    if p_worker_secret is null or p_worker_secret !~ '^[0-9a-f]{64}$' or not exists (
        select 1 from piso_pilot.service_credential c
        where c.secret_sha = encode(sha256(convert_to(p_worker_secret, 'UTF8')), 'hex')
    ) then
        raise exception 'Licensing service authentication required' using errcode = '42501';
    end if;

    select b.* into device from piso_pilot.board b where b.device_sha = p_device_sha;
    if not found then return; end if;

    -- All operations that change ownership or hardware state take the kit lock first.
    select k.* into unit from piso_pilot.kit k where k.id = device.kit_id for update;
    select b.* into device from piso_pilot.board b where b.device_sha = p_device_sha;

    if device.state = 'retired' then
        return query select unit.id, device.mac, device.revision, unit.slot_limit, 'retired'::text;
    elsif unit.owner_user is null then
        return query select unit.id, device.mac, device.revision, unit.slot_limit, 'awaiting_claim'::text;
    elsif device.activation_requested_at is null then
        return query select unit.id, device.mac, device.revision, unit.slot_limit, 'awaiting_operator'::text;
    elsif p_saved_revision is not null and p_saved_revision <> device.revision then
        raise exception 'Grant revision does not match the assigned board' using errcode = '22023';
    elsif device.state = 'active' then
        return query select unit.id, device.mac, device.revision, unit.slot_limit, 'active'::text;
    elsif p_saved_revision is null then
        return query select unit.id, device.mac, device.revision, unit.slot_limit, 'grant_available'::text;
    else
        update piso_pilot.board b set state = 'retired', retired_at = now() where b.kit_id = unit.id and b.state = 'active';
        update piso_pilot.board b set state = 'active', activated_at = now()
            where b.kit_id = unit.id and b.revision = device.revision;
        return query select unit.id, device.mac, device.revision, unit.slot_limit, 'active'::text;
    end if;
end;
$$;

revoke execute on function piso_pilot.require_workshop() from public, anon, authenticated;
revoke execute on function public.pilot_register_kit(text, text, text, integer) from public, anon, authenticated;
revoke execute on function public.pilot_claim_kit(text) from public, anon, authenticated;
revoke execute on function public.pilot_my_kits() from public, anon, authenticated;
revoke execute on function public.pilot_assign_replacement(uuid, text, text) from public, anon, authenticated;
revoke execute on function public.pilot_request_replacement(uuid) from public, anon, authenticated;
revoke execute on function public.pilot_board_check_in(text, text, integer) from public, anon, authenticated;

grant execute on function public.pilot_register_kit(text, text, text, integer) to authenticated;
grant execute on function public.pilot_claim_kit(text) to authenticated;
grant execute on function public.pilot_my_kits() to authenticated;
grant execute on function public.pilot_assign_replacement(uuid, text, text) to authenticated;
grant execute on function public.pilot_request_replacement(uuid) to authenticated;
grant execute on function public.pilot_board_check_in(text, text, integer) to anon;

commit;
