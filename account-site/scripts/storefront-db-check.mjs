import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const { PGlite } = await import(pathToFileURL(resolve(".wrangler/db-test/node_modules/@electric-sql/pglite/dist/index.js")));
const db = new PGlite();
const buyer = "00000000-0000-0000-0000-000000000001";
const other = "00000000-0000-0000-0000-000000000002";
const seller = "00000000-0000-0000-0000-000000000003";
const request = "00000000-0000-0000-0000-000000000010";
const items = JSON.stringify([{ sku: "hub-system", quantity: 2, phone_quantity: 1 }, { sku: "wifi-kit", quantity: 1, phone_quantity: 0 }]);
const place = (key, lines, delivery = "shipping", direct = false) =>
    db.query("select public.store_place_order($1,$2,$3,'Test Buyer','Sample address',$4) id", [key, lines, delivery, direct]);
async function actAs(role, uid = "") {
    await db.exec(`reset role; set role ${role};`);
    await db.query("select set_config('request.jwt.claim.sub',$1,false)", [uid]);
}
async function order(id) {
    return (await db.query("select * from public.store_orders where id = $1", [id])).rows[0];
}

try {
    await db.exec(`
        create role anon; create role authenticated;
        create schema auth;
        create table auth.users (id uuid primary key, email text);
        create function auth.uid() returns uuid language sql as
            $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
        grant usage on schema auth to anon, authenticated;
        create schema storage;
        create table storage.buckets (
            id text primary key, name text, public boolean, file_size_limit bigint, allowed_mime_types text[]
        );
        create table storage.objects (bucket_id text, name text, primary key (bucket_id, name));
        alter table storage.objects enable row level security;
        grant usage on schema storage to authenticated;
        grant select, insert, delete on storage.objects to authenticated;
    `);
    await db.exec(await readFile(new URL("../supabase/storefront.sql", import.meta.url), "utf8"));
    await db.exec(await readFile(new URL("../supabase/product-photos.sql", import.meta.url), "utf8"));
    await db.query("insert into auth.users values ($1,'buyer@example.test'),($2,'other@example.test'),($3,'seller@example.test')", [buyer, other, seller]);
    await db.query("insert into public.store_sellers values ($1)", [seller]);

    await actAs("anon");
    assert.equal((await db.query("select * from public.store_products")).rows.length, 6);
    await assert.rejects(place(request, items), { code: "42501" });
    await assert.rejects(db.query("select * from public.store_orders"), { code: "42501" });
    await actAs("authenticated", buyer);
    await assert.rejects(place(request, items, "pickup", true), { code: "42501" });
    await assert.rejects(place(request, JSON.stringify([{ sku: "wifi-kit", quantity: 1, phone_quantity: 1 }])), { code: "22023" });
    const id = (await place(request, items)).rows[0].id;
    assert.equal((await place(request, items)).rows[0].id, id);
    assert.equal((await order(id)).status, "needs_quote");
    await assert.rejects(db.query("update public.store_orders set status='paid'"), { code: "42501" });
    await assert.rejects(db.query("update public.store_products set price=1"), { code: "42501" });
    await assert.rejects(db.query("select public.store_seller_quote($1, 30000)", [id]), { code: "42501" });
    await assert.rejects(db.query("select public.store_seller_claim_notice($1)", [id]), { code: "42501" });

    await actAs("authenticated", other);
    assert.equal(await order(id), undefined);
    assert.equal((await db.query("select * from public.store_order_lines")).rows.length, 0);
    await assert.rejects(db.query("select public.store_submit_payment($1,'gcash','TEST-123')", [id]), { code: "22023" });

    await actAs("authenticated", seller);
    await db.query("select public.store_seller_product('hub-system',999999,350000,7,'Updated sample listing',true)");
    await db.query("select public.store_seller_quote($1, 30000)", [id]);
    assert.equal(Number((await order(id)).total), 2530000);
    assert.equal((await db.query("select price from public.store_order_lines where order_id=$1 and sku='hub-system'", [id])).rows[0].price, 850000);
    const [{ recipient, lease }] = (await db.query("select * from public.store_seller_claim_notice($1)", [id])).rows;
    assert.equal(recipient, "buyer@example.test");
    assert.equal((await db.query("select * from public.store_seller_claim_notice($1)", [id])).rows.length, 0);
    await db.query("select public.store_seller_finish_notice($1,$2,false)", [id, lease]);
    const retry = (await db.query("select * from public.store_seller_claim_notice($1)", [id])).rows[0];
    await db.query("select public.store_seller_finish_notice($1,$2,true)", [id, retry.lease]);
    assert.equal((await db.query("select status from public.store_quote_notices")).rows[0].status, "sent");

    await actAs("authenticated", buyer);
    await db.query("select public.store_submit_payment($1,'gcash','test-123')", [id]);
    await db.query("select public.store_submit_payment($1,'gcash','TEST-123')", [id]);
    assert.equal((await order(id)).status, "payment_review");
    await assert.rejects(db.query("select public.store_seller_verify($1,2530000)", [id]), { code: "42501" });

    await actAs("authenticated", other);
    const pickup = (await place(request, items, "pickup")).rows[0].id;
    assert.equal((await order(pickup)).shipping, 0);
    await assert.rejects(db.query("select public.store_submit_payment($1,'gcash','test-123')", [pickup]), { code: "22023" });

    await actAs("authenticated", seller);
    await assert.rejects(db.query("select public.store_seller_verify($1,1)", [id]), { code: "22023" });
    await db.query("select public.store_seller_verify($1,2530000)", [id]);
    assert.equal((await order(id)).status, "paid");
    const direct = (await place(request, items, "pickup", true)).rows[0].id;
    assert.equal((await order(direct)).buyer_id, null);
    await db.query("select public.store_submit_payment($1,'bank','DIRECT-123')", [direct]);
    assert.equal((await order(direct)).status, "payment_review");
    await actAs("authenticated", buyer);
    assert.equal(await order(direct), undefined);
    const photo = "hub-kit/00000000-0000-0000-0000-000000000009.png";
    const otherPhoto = "wifi-kit/00000000-0000-0000-0000-000000000009.png";
    const upload = (path) => db.query("insert into storage.objects values ('store-product-photos', $1)", [path]);
    const setPhoto = (sku, prior, path) => db.query("select public.store_seller_photo($1,$2,$3)", [sku, prior, path]);
    const deletePhoto = (path) => db.query("delete from storage.objects where name = $1", [path]);
    await assert.rejects(upload(photo), { code: "42501" });
    await assert.rejects(setPhoto("hub-kit", null, photo), { code: "42501" });
    await actAs("authenticated", seller);
    await assert.rejects(upload("not-a-product/00000000-0000-0000-0000-000000000009.png"), { code: "42501" });
    await assert.rejects(setPhoto("hub-kit", null, photo), { code: "22023" });
    await upload(photo);
    await upload(otherPhoto);
    await assert.rejects(setPhoto("hub-kit", null, otherPhoto), { code: "22023" });
    await setPhoto("hub-kit", null, photo);
    await assert.rejects(setPhoto("hub-kit", null, photo), { code: "22023" });
    assert.equal((await deletePhoto(photo)).affectedRows, 0);
    await actAs("anon");
    assert.equal((await db.query("select image_path from public.store_products where sku='hub-kit'")).rows[0].image_path, photo);
    await actAs("authenticated", seller);
    await setPhoto("hub-kit", photo, null);
    assert.equal((await deletePhoto(photo)).affectedRows, 1);
    console.log("Passed storefront order, catalog, permission, and product photo checks.");
} finally {
    await db.close();
}
