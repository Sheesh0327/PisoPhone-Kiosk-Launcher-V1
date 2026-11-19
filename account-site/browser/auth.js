import config from "./config.js";

export const supabase = globalThis.supabase.createClient(config.supabaseUrl, config.publishableKey, {
    auth: { flowType: "pkce", detectSessionInUrl: false }
});

export const accountApi = config.workerUrl;
