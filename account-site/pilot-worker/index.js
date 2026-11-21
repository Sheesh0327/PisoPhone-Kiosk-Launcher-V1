const encoder = new TextEncoder();
const digestFormat = /^[a-f0-9]{64}$/;
const uuidFormat = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const pending = new Set(["awaiting_claim", "awaiting_operator"]);
const licenseKinds = { grant_available: "offer", active: "active", retired: "retired" };

function reply(status, data, extraHeaders = {}) {
    return Response.json(data, {
        status,
        headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", ...extraHeaders }
    });
}

function toHex(bytes) {
    return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function fromHex(text) {
    return Uint8Array.from(text.match(/../g), (byte) => Number.parseInt(byte, 16));
}

function canonicalGrant(assignment, kind) {
    return ["piso-pilot-v1", kind, assignment.kit_id, assignment.mac, assignment.revision, assignment.slot_limit].join("|");
}

// Web Crypto produces two 32-byte integers. ESP32's existing ECDSA verifier uses DER.
function derSignature(raw) {
    function integer(component) {
        let start = 0;
        while (start < component.length - 1 && component[start] === 0) start++;
        const bytes = Array.from(component.subarray(start));
        if ((bytes[0] & 0x80) !== 0) bytes.unshift(0);
        return [0x02, bytes.length, ...bytes];
    }

    const numbers = [...integer(raw.subarray(0, 32)), ...integer(raw.subarray(32, 64))];
    return btoa(String.fromCharCode(0x30, numbers.length, ...numbers));
}

async function makeGrant(assignment, kind, key) {
    const message = canonicalGrant(assignment, kind);
    const signature = await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" }, key, encoder.encode(message)
    );
    return { message, signature: derSignature(new Uint8Array(signature)) };
}

async function checkIn(env, deviceDigest, savedRevision = null) {
    const response = await fetch(new URL("/rest/v1/rpc/pilot_board_check_in", env.PILOT_SUPABASE_URL), {
        method: "POST",
        headers: {
            apikey: env.PILOT_SUPABASE_PUBLISHABLE_KEY,
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            p_worker_secret: env.PILOT_DATABASE_CREDENTIAL, p_device_sha: deviceDigest, p_saved_revision: savedRevision
        }),
        redirect: "manual",
        signal: AbortSignal.timeout(8000)
    });
    if (!response.ok) {
        console.warn("Pilot storage returned an error", response.status);
        throw new Error("Pilot storage request failed");
    }
    const result = await response.json();
    if (!Array.isArray(result) || result.length > 1) throw new Error("Invalid pilot storage response");
    const [assignment] = result;
    if (assignment && (!uuidFormat.test(assignment.kit_id) || !/^[a-f0-9]{12}$/.test(assignment.mac) ||
        !Number.isSafeInteger(assignment.revision) || assignment.revision < 1 ||
        !Number.isInteger(assignment.slot_limit) || assignment.slot_limit < 2 || assignment.slot_limit > 12 ||
        !(pending.has(assignment.status) || Object.hasOwn(licenseKinds, assignment.status)))) {
        throw new Error("Invalid assignment");
    }
    return assignment;
}

async function loadKeys(env) {
    if (!/^https:\/\/[a-z0-9-]+\.supabase\.co\/?$/.test(env.PILOT_SUPABASE_URL ?? "") ||
        !env.PILOT_SUPABASE_PUBLISHABLE_KEY?.startsWith("sb_publishable_") ||
        !digestFormat.test(env.PILOT_DATABASE_CREDENTIAL ?? "") ||
        !digestFormat.test(env.PILOT_RECEIPT_KEY ?? "") || !env.PILOT_GRANT_PRIVATE_JWK) {
        throw new Error("Pilot licensing is not configured");
    }
    const signing = await crypto.subtle.importKey(
        "jwk", JSON.parse(env.PILOT_GRANT_PRIVATE_JWK), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]
    );
    const receipt = await crypto.subtle.importKey(
        "raw", fromHex(env.PILOT_RECEIPT_KEY), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]
    );
    return { signing, receipt };
}

export default {
    async fetch(request, env) {
        if (new URL(request.url).pathname !== "/v1/check-in") return reply(404, { error: "not_found" });
        if (request.method !== "GET" && request.method !== "POST") {
            return reply(405, { error: "method_not_allowed" }, { Allow: "GET, POST" });
        }
        if (request.headers.has("Origin")) return reply(403, { error: "device_only" });

        const credential = /^Bearer ([a-f0-9]{64})$/i.exec(request.headers.get("Authorization") ?? "")?.[1]?.toLowerCase();
        if (!credential) return reply(401, { error: "device_credential_required" });
        const deviceDigest = toHex(await crypto.subtle.digest("SHA-256", encoder.encode(credential)));
        const acknowledging = request.method === "POST";
        const revisionText = request.headers.get("X-Pilot-Saved-Revision") ?? "";
        const receipt = request.headers.get("X-Pilot-Grant-Receipt") ?? "";
        if (acknowledging && (!/^[1-9]\d{0,9}$/.test(revisionText) || !digestFormat.test(receipt))) {
            return reply(400, { error: "invalid_acknowledgement" });
        }

        let phase = "configuration";
        try {
            const keys = await loadKeys(env);
            phase = "assignment lookup";
            const assignment = await checkIn(env, deviceDigest);
            if (!assignment) return reply(401, { error: "unknown_device" });
            const { status } = assignment;
            if (pending.has(status)) return reply(200, { status });
            phase = "grant";
            const kind = licenseKinds[status];

            if (!acknowledging || status === "retired") {
                const grant = await makeGrant(assignment, kind, keys.signing);
                if (kind !== "offer") return reply(200, { status, grant });
                const offerBytes = encoder.encode(canonicalGrant(assignment, "offer"));
                const grantReceipt = toHex(await crypto.subtle.sign("HMAC", keys.receipt, offerBytes));
                return reply(200, { status, grant, receipt: grantReceipt });
            }

            const offerBytes = encoder.encode(canonicalGrant(assignment, "offer"));
            if (Number(revisionText) !== assignment.revision ||
                !await crypto.subtle.verify("HMAC", keys.receipt, fromHex(receipt), offerBytes)) {
                return reply(409, { error: "grant_mismatch" });
            }

            // Prepare the active signature before requesting cutover. Only return it if storage confirms cutover.
            const grant = await makeGrant(assignment, "active", keys.signing);
            const confirmed = await checkIn(env, deviceDigest, assignment.revision);
            if (confirmed?.status === "active") return reply(200, { status: "active", grant });
            if (confirmed?.status === "retired") {
                return reply(200, { status: "retired", grant: await makeGrant(confirmed, "retired", keys.signing) });
            }
            return reply(409, { error: "assignment_changed" });
        } catch {
            console.warn("Pilot licensing unavailable during", phase);
            return reply(503, { error: "pilot_licensing_unavailable" });
        }
    }
};
