begin;

alter table public.store_products add column image_path text;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('store-product-photos', 'store-product-photos', true, 5 * 1024 * 1024,
    array['image/jpeg', 'image/png', 'image/webp']);

create policy store_photo_upload on storage.objects for insert to authenticated
with check (
    bucket_id = 'store-product-photos'
    and (select public.store_is_seller())
    and name ~ '^[a-z0-9-]+/[0-9a-f-]{36}[.](jpg|png|webp)$'
    and exists (select from public.store_products p where p.sku = split_part(objects.name, '/', 1))
);

create policy store_photo_seller_select on storage.objects for select to authenticated
using (bucket_id = 'store-product-photos' and (select public.store_is_seller()));

create policy store_photo_delete_unused on storage.objects for delete to authenticated
using (
    bucket_id = 'store-product-photos'
    and (select public.store_is_seller())
    and not exists (select from public.store_products p where p.image_path = objects.name)
);

create function public.store_seller_photo(p_sku text, p_previous text, p_path text) returns void
language plpgsql security definer set search_path = ''
as $$
begin
    if not public.store_is_seller() then raise insufficient_privilege; end if;
    if p_path is not null then
        if p_path !~ '^[a-z0-9-]+/[0-9a-f-]{36}[.](jpg|png|webp)$' or split_part(p_path, '/', 1) <> p_sku
            or not exists (select from storage.objects o where o.bucket_id = 'store-product-photos' and o.name = p_path) then
            raise exception 'The uploaded photo could not be found for this listing.' using errcode = '22023';
        end if;
    end if;
    update public.store_products set image_path = p_path
        where sku = p_sku and image_path is not distinct from p_previous;
    if not found then
        raise exception 'This photo changed in another tab. Reload the listing and try again.' using errcode = '22023';
    end if;
    insert into public.store_events (actor, action)
        values (auth.uid(), (case when p_path is null then 'photo_removed:' else 'photo_uploaded:' end) || p_sku);
end;
$$;

revoke all on function public.store_seller_photo(text,text,text) from public, anon, authenticated;
grant execute on function public.store_seller_photo(text,text,text) to authenticated;

commit;
