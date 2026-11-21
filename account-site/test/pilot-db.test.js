import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { PGlite } from "@electric-sql/pglite";

const staff = "00000000-0000-0000-0000-000000000001";
const owner = "00000000-0000-0000-0000-000000000002";
const stranger = "00000000-0000-0000-0000-000000000003";
const code = "ab".repeat(16);
const macs = ["aabbccddee01", "aabbccddee02", "aabbccddee03"];
const credentials = ["01", "02", "03"].map((part) => digest(part.repeat(32)));
const workerSecret = "34".repeat(32);
const db = new PGlite();

function digest(value) {
    return createHash("sha256").update(value).digest("hex");
}

async function as(role, actor, query, params = []) {
    return db.transaction(async (tx) => {
        await tx.exec("set local role " + role);
        await tx.query("select set_config('request.jwt.claim.sub', $1, true)", [actor ?? ""]);
        return (await tx.query(query, params)).rows;
    });
}

before(async () => {
    await db.exec(`
        create role anon;
        create role authenticated;

        create schema auth;
        create function auth.uid() returns uuid language sql stable as
        $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
        create table public.store_sellers (user_id uuid primary key);
        create function public.store_is_seller() returns boolean language sql stable as
        $$ select exists (select 1 from public.store_sellers where user_id = auth.uid()) $$;
    `);
    await db.query("insert into public.store_sellers values ($1)", [staff]);
    await db.exec(await readFile(new URL("../supabase/pisophone-pilot.sql", import.meta.url), "utf8"));
    await db.query("insert into piso_pilot.service_credential (secret_sha) values ($1)", [digest(workerSecret)]);
});

after(async () => db.close());

test("claim ownership is private and replacement keeps slots with a single active board", async () => {
    const register = "select public.pilot_register_kit($1, $2, $3, $4) as id";
    const registration = [digest(code), macs[0], credentials[0], 4];
    const claim = "select public.pilot_claim_kit($1) as id";
    const lookup = "select * from public.pilot_my_kits()";
    const checkIn = (credential, saved = null) => as("anon", null,
        "select * from public.pilot_board_check_in($1, $2, $3)", [workerSecret, credential, saved]);
    const assign = "select public.pilot_assign_replacement($1, $2, $3) as revision";
    const request = "select public.pilot_request_replacement($1) as requested";

    await assert.rejects(as("authenticated", stranger, register, registration), { code: "42501" });
    await assert.rejects(as("anon", null, claim, [code]), { code: "42501" });
    const [{ id }] = await as("authenticated", staff, register, registration);
    assert.ok(id);
    assert.deepEqual(await as("authenticated", staff, register, registration), [{ id }]);
    assert.equal((await checkIn(credentials[0]))[0].status, "awaiting_claim");
    assert.deepEqual(await checkIn(digest("unknown")), []);
    await assert.rejects(as("anon", null,
        "select * from public.pilot_board_check_in($1, $2, null)", [digest("wrong"), credentials[0]]), { code: "42501" });
    await assert.rejects(as("authenticated", owner,
        "select * from public.pilot_board_check_in($1, $2, null)", [workerSecret, credentials[0]]), { code: "42501" });
    assert.deepEqual(await as("authenticated", owner, claim, ["invalid"]), [{ id: null }]);

    const firstClaim = await as("authenticated", owner, claim, [code.toUpperCase()]);
    assert.deepEqual(firstClaim, [{ id }]);
    assert.deepEqual(await as("authenticated", stranger, claim, [code]), [{ id: null }]);
    assert.deepEqual(await as("authenticated", owner, claim, [code]), firstClaim);
    assert.deepEqual(await as("authenticated", stranger, lookup), []);
    await assert.rejects(as("authenticated", owner, "select * from piso_pilot.board"), { code: "42501" });

    assert.equal((await checkIn(credentials[0]))[0].status, "grant_available");
    assert.equal((await as("authenticated", owner, lookup))[0].active_mac, null);
    await assert.rejects(checkIn(credentials[0], 2), { code: "22023" });
    assert.equal((await checkIn(credentials[0], 1))[0].status, "active");

    for (let index = 1; index < macs.length; index++) {
        const revision = index + 1;
        const assignment = [id, macs[index], credentials[index]];
        await assert.rejects(as("authenticated", owner, assign, assignment), { code: "42501" });
        assert.deepEqual(await as("authenticated", staff, assign, assignment), [{ revision }]);
        assert.deepEqual(await as("authenticated", staff, assign, assignment), [{ revision }]);
        assert.equal((await checkIn(credentials[index]))[0].status, "awaiting_operator");
        assert.equal((await checkIn(credentials[index], revision))[0].status, "awaiting_operator");
        assert.deepEqual(await as("authenticated", stranger, request, [id]), [{ requested: false }]);
        assert.deepEqual(await as("authenticated", owner, request, [id]), [{ requested: true }]);
        assert.deepEqual(await as("authenticated", owner, request, [id]), [{ requested: true }]);
        assert.equal((await as("authenticated", owner, lookup))[0].active_revision, index);
        assert.equal((await checkIn(credentials[index]))[0].status, "grant_available");
        assert.equal((await checkIn(credentials[index]))[0].slot_limit, 4);
        assert.equal((await checkIn(credentials[index - 1]))[0].status, "active");
        const result = (await checkIn(credentials[index], revision))[0];
        assert.equal(result.status, "active");
        assert.equal(result.slot_limit, 4);
        assert.deepEqual((await checkIn(credentials[index], revision))[0], result);
        assert.equal((await checkIn(credentials[index - 1], index))[0].status, "retired");
        assert.deepEqual(await as("authenticated", owner, lookup), [{
            id, slot_limit: 4, active_mac: macs[index], active_revision: revision,
            assigned_mac: null, assigned_revision: null, activation_requested: false
        }]);
    }
});
