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
        const notice = /^\/api\/store\/quote-email\/([0-9a-f-]{36})$/i.exec(pathname);
        const allowedMethod = notice ? "POST" : "GET";
        if (pathname !== "/api/me" && !notice) return reply(404, { error: "not_found" });

        if (!env.PAGES_ORIGIN || !env.SUPABASE_URL ||
            !env.SUPABASE_PUBLISHABLE_KEY?.startsWith("sb_publishable_")) {
            return reply(503, { error: "account_service_unavailable" });
        }
        if (origin) {
            if (origin !== env.PAGES_ORIGIN) return reply(403, { error: "origin_not_allowed" });
            headers.set("Access-Control-Allow-Origin", origin);
        }
        if (request.method === "OPTIONS" && origin &&
            request.headers.get("Access-Control-Request-Method") === allowedMethod) {
            headers.set("Access-Control-Allow-Methods", allowedMethod);
            headers.set("Access-Control-Allow-Headers", "Authorization");
            return new Response(null, { status: 204, headers });
        }
        if (request.method !== allowedMethod) {
            headers.set("Allow", allowedMethod + ", OPTIONS");
            return reply(405, { error: "method_not_allowed" });
        }

        const token = /^Bearer (\S+)$/i.exec(request.headers.get("Authorization") ?? "")?.[1];
        if (!token) {
            headers.set("WWW-Authenticate", "Bearer");
            return reply(401, { error: "sign_in_required" });
        }

        if (notice) return sendQuoteNotice(notice[1], token, env, reply);

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


async function storeRpc(operation, payload, token, env) {
    return fetch(new URL(`/rest/v1/rpc/${operation}`, env.SUPABASE_URL), {
        method: "POST",
        headers: {
            apikey: env.SUPABASE_PUBLISHABLE_KEY,
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json"
        },
        body: JSON.stringify(payload),
        redirect: "manual",
        signal: AbortSignal.timeout(8000)
    });
}

async function sendQuoteNotice(orderId, token, env, reply) {
    if (!env.STORE_MAIL_API_KEY || !env.STORE_MAIL_FROM || !env.STORE_TEST_EMAIL_TO) {
        return reply(503, { error: "quote_email_not_configured" });
    }
    let claim;
    try {
        const response = await storeRpc("store_seller_claim_notice", { p_order_id: orderId }, token, env);
        if ([401, 403].includes(response.status)) return reply(response.status, { error: "seller_required" });
        if (!response.ok) return reply(503, { error: "quote_email_unavailable" });
        [claim] = await response.json();
        if (!claim) return reply(409, { error: "quote_email_already_sent_or_unavailable" });
    } catch {
        return reply(503, { error: "quote_email_unavailable" });
    }

    let sent = false;
    try {
        // Explicit fixture allowlist prevents emails to arbitrary destinations from the public test store.
        if (claim.recipient.toLowerCase() === env.STORE_TEST_EMAIL_TO.toLowerCase()) {
            const orderUrl = new URL("/account/store/", env.PAGES_ORIGIN);
            orderUrl.searchParams.set("order", orderId);
            const total = new Intl.NumberFormat("en-PH", { style: "currency", currency: "PHP" }).format(claim.total / 100);
            const mail = await fetch("https://api.resend.com/emails", {
                method: "POST",
                headers: {
                    Authorization: `Bearer ${env.STORE_MAIL_API_KEY}`,
                    "Content-Type": "application/json",
                    "Idempotency-Key": `pisophone-preview-quote-${orderId}`
                },
                body: JSON.stringify({
                    from: env.STORE_MAIL_FROM, to: [claim.recipient],
                    subject: "Your PisoPhone test shipping quote",
                    text: `Your test order total is ${total}. Order reference: ${orderId}. View the quote: ${orderUrl.href}\nDo not send a real payment.`
                }),
                redirect: "manual",
                signal: AbortSignal.timeout(8000)
            });
            sent = mail.ok;
        }
    } catch {
        sent = false;
    }
    try {
        const result = await storeRpc("store_seller_finish_notice", {
            p_order_id: orderId, p_lease: claim.lease, p_sent: sent
        }, token, env);
        if (sent && result.ok) return reply(200, { sent: true });
    } catch {
        // The short database lease expires if the delivery result cannot be saved.
    }
    return reply(503, { error: "quote_email_unavailable" });
}
