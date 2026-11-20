import { accountApi, supabase } from "./auth.js";

const element = (id) => document.getElementById(id);
const status = element("status");
const signedIn = element("signed-in");
const signedOut = element("signed-out");
const requestCode = element("request-code");
const verifyCode = element("verify-code");
const email = element("email");
const code = element("code");
const retry = element("retry");
const identity = element("identity");
let session;
let accountRequest;
let busy = false;

function clearIdentity() {
    identity.hidden = true;
    element("verified-id").textContent = "";
    element("verified-email").textContent = "";
}

async function showSession(nextSession) {
    accountRequest?.abort();
    session = nextSession;
    clearIdentity();
    signedIn.hidden = !session;
    signedOut.hidden = Boolean(session);
    retry.hidden = true;
    status.textContent = session ? "Checking your account…" : "";
    if (!session) return;

    const controller = new AbortController();
    accountRequest = controller;
    try {
        const response = await fetch(`${accountApi}/api/me`, {
            cache: "no-store",
            headers: { Authorization: `Bearer ${session.access_token}` },
            signal: AbortSignal.any([controller.signal, AbortSignal.timeout(12000)])
        });
        if (controller.signal.aborted) return;
        if (response.status === 401) {
            status.textContent = "Your session could not be verified. Sign out and sign in again.";
            return;
        }
        if (!response.ok) throw new Error();
        const user = await response.json();
        if (controller.signal.aborted) return;
        if (typeof user.id !== "string" || (user.email !== null && typeof user.email !== "string")) {
            throw new Error();
        }
        element("verified-id").textContent = user.id;
        element("verified-email").textContent = user.email ?? "Unavailable";
        identity.hidden = false;
        status.textContent = "Signed in.";
        const returnKey = "pisophone-preview-store-return";
        const returnTo = sessionStorage.getItem(returnKey);
        if (returnTo) {
            sessionStorage.removeItem(returnKey);
            const store = new URL("/account/store/", location.origin);
            if (/^[0-9a-f-]{36}$/i.test(returnTo)) store.searchParams.set("order", returnTo);
            location.replace(store.href);
        }
    } catch {
        if (controller.signal.aborted) return;
        status.textContent = "Account verification is unavailable. Try again in a moment.";
        retry.hidden = false;
    }
}

async function submit(action, failureMessage) {
    if (busy) return;
    busy = true;
    for (const button of document.querySelectorAll("button")) button.disabled = true;
    try {
        await action();
    } catch {
        status.textContent = failureMessage;
    } finally {
        for (const button of document.querySelectorAll("button")) button.disabled = false;
        busy = false;
    }
}

element("google").addEventListener("click", () => {
    submit(async () => {
        status.textContent = "Opening Google…";
        const { error } = await supabase.auth.signInWithOAuth({
            provider: "google",
            options: { redirectTo: new URL("/account/callback/", location.origin).href }
        });
        if (error) throw error;
    }, "Could not start Google sign-in. Try again.");
});

requestCode.addEventListener("submit", (event) => {
    event.preventDefault();
    submit(async () => {
        status.textContent = "Requesting a code…";
        const address = email.value.trim();
        const { error } = await supabase.auth.signInWithOtp({ email: address });
        if (error) throw error;
        email.value = address;
        email.readOnly = true;
        verifyCode.hidden = false;
        status.textContent = `Enter the code sent to ${address}. Check spam if it does not arrive.`;
        code.focus();
    }, "Could not send an email code. Check the address or wait before requesting another.");
});

verifyCode.addEventListener("submit", (event) => {
    event.preventDefault();
    submit(async () => {
        status.textContent = "Verifying your code…";
        const { error } = await supabase.auth.verifyOtp({
            email: email.value,
            token: code.value.trim(),
            type: "email"
        });
        if (error) throw error;
        verifyCode.reset();
        verifyCode.hidden = true;
        email.readOnly = false;
        requestCode.reset();
    }, "The code could not be verified. Check it or request a new one.");
});

element("change-email").addEventListener("click", () => {
    email.readOnly = false;
    verifyCode.reset();
    verifyCode.hidden = true;
    status.textContent = "";
    email.focus();
});

element("sign-out").addEventListener("click", () => {
    submit(async () => {
        accountRequest?.abort();
        clearIdentity();
        const { error } = await supabase.auth.signOut({ scope: "local" });
        if (error) throw error;
    }, "Could not sign out. Try again.");
});

retry.addEventListener("click", () => showSession(session));

let renderTimer;
supabase.auth.onAuthStateChange((_event, nextSession) => {
    // Supabase holds the session lock during this callback.
    clearTimeout(renderTimer);
    accountRequest?.abort();
    renderTimer = setTimeout(() => showSession(nextSession), 0);
});
