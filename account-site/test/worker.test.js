import assert from "node:assert/strict";
import { test } from "node:test";
import worker from "../worker/index.js";

const origin = "https://pisophone-accounts-test.pages.dev";
const env = {
    SUPABASE_URL: "https://auth.example.test",
    SUPABASE_PUBLISHABLE_KEY: "sb_publishable_test",
    PAGES_ORIGIN: origin
};

function request(path, init, bindings = env) {
    return worker.fetch(new Request(`https://worker.example.test${path}`, init), bindings);
}

test("health and routing", async () => {
    assert.deepEqual(await (await request("/health", undefined, {})).json(), { ok: true });
    assert.equal((await request("/unknown")).status, 404);
    assert.equal((await request("/api/me", { method: "POST" })).status, 405);
});

test("only the configured browser origin is allowed", async () => {
    const init = {
        method: "OPTIONS",
        headers: { Origin: origin, "Access-Control-Request-Method": "GET" }
    };
    const allowed = await request("/api/me", init);
    assert.equal(allowed.status, 204);
    assert.equal(allowed.headers.get("Access-Control-Allow-Origin"), origin);
    assert.equal(allowed.headers.get("Access-Control-Allow-Headers"), "Authorization");

    const denied = await request("/api/me", { ...init, headers: { ...init.headers, Origin: "https://other.test" } });
    assert.equal(denied.status, 403);
    assert.equal(denied.headers.get("Access-Control-Allow-Origin"), null);
});

test("anonymous and misconfigured requests are rejected", async () => {
    const response = await request("/api/me", { headers: { Origin: origin } });
    assert.equal(response.status, 401);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.equal(response.headers.get("Access-Control-Allow-Origin"), origin);
    assert.equal((await request("/api/me", { headers: { Authorization: "Basic dGVzdA==" } })).status, 401);
    assert.equal((await request("/api/me", undefined, {})).status, 503);
});

test("returns only the identity Supabase verified for each token", async (t) => {
    const users = new Map([
        ["Bearer first-token", { id: "first", email: "first@example.test", app_metadata: { role: "test" } }],
        ["Bearer second-token", { id: "second", email: "second@example.test", phone: "private" }]
    ]);
    t.mock.method(globalThis, "fetch", async (url, { headers, redirect }) => {
        assert.equal(redirect, "manual");
        assert.equal(String(url), "https://auth.example.test/auth/v1/user");
        const sent = new Headers(headers);
        assert.equal(sent.get("apikey"), env.SUPABASE_PUBLISHABLE_KEY);
        return Response.json(users.get(sent.get("Authorization")));
    });

    for (const [authorization, user] of users) {
        const response = await request("/api/me", { headers: { Authorization: authorization } });
        assert.equal(response.status, 200);
        assert.equal(response.headers.get("Cache-Control"), "no-store");
        assert.deepEqual(await response.json(), { id: user.id, email: user.email });
    }
});

test("distinguishes invalid tokens from an Auth outage", async (t) => {
    const init = { headers: { Authorization: "Bearer invalid-token" } };
    const upstream = t.mock.method(globalThis, "fetch", async () =>
        Response.json({ message: "Invalid token" }, { status: 403 }));
    assert.equal((await request("/api/me", init)).status, 401);
    upstream.mock.mockImplementation(async () => Response.redirect("https://elsewhere.example.test/"));
    assert.equal((await request("/api/me", init)).status, 503);
    upstream.mock.mockImplementation(async () => Response.json({}, { status: 503 }));
    assert.equal((await request("/api/me", init)).status, 503);
});
