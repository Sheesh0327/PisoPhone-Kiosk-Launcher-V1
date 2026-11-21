import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, verify, createHash } from "node:crypto";
import worker from "../pilot-worker/index.js";

test("board grants require device credentials and activate only after a valid save receipt", async (t) => {
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const env = {
        PILOT_SUPABASE_URL: "https://beta-project.supabase.co",
        PILOT_SUPABASE_PUBLISHABLE_KEY: "sb_publishable_pilot_fixture",
        PILOT_DATABASE_CREDENTIAL: "12".repeat(32),
        PILOT_RECEIPT_KEY: "ab".repeat(32),
        PILOT_GRANT_PRIVATE_JWK: JSON.stringify(privateKey.export({ format: "jwk" }))
    };
    const credential = "a1".repeat(32);
    const digest = createHash("sha256").update(credential).digest("hex");
    const row = { kit_id: "11d3c6ec-2992-409e-b39f-b0161a83d863", mac: "aabbccddee02", revision: 2, slot_limit: 4 };
    let status = "awaiting_operator";
    let writes = 0;
    t.mock.method(globalThis, "fetch", async (url, options) => {
        assert.equal(url.href, "https://beta-project.supabase.co/rest/v1/rpc/pilot_board_check_in");
        assert.equal(options.headers.apikey, env.PILOT_SUPABASE_PUBLISHABLE_KEY);
        const input = JSON.parse(options.body);
        assert.equal(input.p_worker_secret, env.PILOT_DATABASE_CREDENTIAL);
        if (input.p_device_sha !== digest) return Response.json([]);
        if (input.p_saved_revision !== null) {
            assert.equal(input.p_saved_revision, 2);
            writes++;
            status = "active";
        }
        return Response.json([{ ...row, status }]);
    });

    function check(method = "GET", options = {}) {
        return worker.fetch(new Request("https://pilot.example/v1/check-in", {
            method, headers: { Authorization: "Bearer " + credential, ...options }
        }), env);
    }

    function assertSignature(grant, kind) {
        assert.equal(grant.message, ["piso-pilot-v1", kind, row.kit_id, row.mac, 2, 4].join("|"));
        assert.equal(verify("sha256", Buffer.from(grant.message), publicKey, Buffer.from(grant.signature, "base64")), true);
    }

    assert.equal((await check("GET", { Authorization: "" })).status, 401);
    assert.equal((await check("GET", { Authorization: "Bearer " + "ff".repeat(32) })).status, 401);
    assert.equal((await check("GET", { Origin: "https://other.example" })).status, 403);
    assert.deepEqual(await (await check()).json(), { status: "awaiting_operator" });

    status = "grant_available";
    const offer = await (await check()).json();
    assert.equal(offer.status, "grant_available");
    assertSignature(offer.grant, "offer");
    assert.equal(writes, 0);
    const acknowledge = (revision, receipt) => check("POST", {
        "X-Pilot-Saved-Revision": revision, "X-Pilot-Grant-Receipt": receipt
    });

    assert.equal((await acknowledge("2", "00".repeat(32))).status, 409);
    assert.equal((await acknowledge("1", offer.receipt)).status, 409);
    assert.equal(writes, 0);

    for (let retry = 0; retry < 2; retry++) {
        const result = await (await acknowledge("2", offer.receipt)).json();
        assert.equal(result.status, "active");
        assertSignature(result.grant, "active");
    }

    status = "retired";
    const retirement = await (await acknowledge("2", offer.receipt)).json();
    assert.equal(retirement.status, "retired");
    assertSignature(retirement.grant, "retired");
    assert.equal(writes, 2);
});
