import { supabase } from "./auth.js";

const url = new URL(location.href);
const code = url.searchParams.get("code");
history.replaceState(null, "", url.pathname);

try {
    if (!code || url.searchParams.has("error")) throw new Error();
    const { data, error } = await supabase.auth.exchangeCodeForSession(code);
    if (error || !data.session) throw new Error();
    location.replace("/account/");
} catch {
    document.getElementById("status").textContent =
        "Google sign-in could not be completed. Return to your account and start again in this browser.";
}
