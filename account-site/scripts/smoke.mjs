import assert from "node:assert/strict";

const api = new URL(process.env.ACCOUNT_API_URL);
const origin = new URL(process.env.PAGES_ORIGIN).origin;
const me = new URL("/api/me", api);
const options = { signal: AbortSignal.timeout(15000), redirect: "error" };
const health = await fetch(new URL("/health", api), options);
assert.equal(health.status, 200);
assert.deepEqual(await health.json(), { ok: true });

const preflight = await fetch(me, {
    ...options,
    method: "OPTIONS",
    headers: { Origin: origin, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization" }
});
assert.equal(preflight.status, 204);
assert.equal(preflight.headers.get("Access-Control-Allow-Origin"), origin);
assert.match(preflight.headers.get("Access-Control-Allow-Headers") ?? "", /\bauthorization\b/i);

for (const headers of [{ Origin: origin }, { Origin: origin, Authorization: "Bearer invalid-token" }]) {
    const response = await fetch(me, { ...options, headers });
    assert.equal(response.status, 401);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.equal(response.headers.get("Access-Control-Allow-Origin"), origin);
}
console.log("Worker health, preview CORS, and anonymous/invalid session rejection passed.");
