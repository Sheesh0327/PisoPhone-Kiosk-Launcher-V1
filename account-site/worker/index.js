export default {
    async fetch(request, env) {
        const { pathname } = new URL(request.url);
        const origin = request.headers.get("Origin");
        const headers = new Headers({
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
            "Vary": "Origin"
        });
        const reply = (status, data) => Response.json(data, { status, headers });

        if (pathname === "/health" && request.method === "GET") {
            return reply(200, { ok: true });
        }
        if (pathname !== "/api/me") return reply(404, { error: "not_found" });

        if (!env.PAGES_ORIGIN || !env.SUPABASE_URL ||
            !env.SUPABASE_PUBLISHABLE_KEY?.startsWith("sb_publishable_")) {
            return reply(503, { error: "account_service_unavailable" });
        }
        if (origin) {
            if (origin !== env.PAGES_ORIGIN) return reply(403, { error: "origin_not_allowed" });
            headers.set("Access-Control-Allow-Origin", origin);
        }
        if (request.method === "OPTIONS" && origin &&
            request.headers.get("Access-Control-Request-Method") === "GET") {
            headers.set("Access-Control-Allow-Methods", "GET");
            headers.set("Access-Control-Allow-Headers", "Authorization");
            return new Response(null, { status: 204, headers });
        }
        if (request.method !== "GET") {
            headers.set("Allow", "GET, OPTIONS");
            return reply(405, { error: "method_not_allowed" });
        }

        const token = /^Bearer (\S+)$/i.exec(request.headers.get("Authorization") ?? "")?.[1];
        if (!token) {
            headers.set("WWW-Authenticate", "Bearer");
            return reply(401, { error: "sign_in_required" });
        }

        try {
            const userResponse = await fetch(new URL("/auth/v1/user", env.SUPABASE_URL), {
                headers: {
                    apikey: env.SUPABASE_PUBLISHABLE_KEY,
                    Authorization: `Bearer ${token}`
                },
                redirect: "manual",
                signal: AbortSignal.timeout(8000)
            });
            if ([400, 401, 403].includes(userResponse.status)) {
                headers.set("WWW-Authenticate", 'Bearer error="invalid_token"');
                return reply(401, { error: "invalid_session" });
            }
            if (!userResponse.ok) return reply(503, { error: "account_service_unavailable" });
            const user = await userResponse.json();
            if (typeof user?.id !== "string" || !user.id) {
                return reply(503, { error: "account_service_unavailable" });
            }
            return reply(200, { id: user.id, email: typeof user.email === "string" ? user.email : null });
        } catch {
            return reply(503, { error: "account_service_unavailable" });
        }
    }
};
