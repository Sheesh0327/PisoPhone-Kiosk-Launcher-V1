import { supabase } from "../../auth.js";
import { pilotWifiCommand, readWorkshopBoard, sendToWorkshopBoard } from "./workshop-usb.js";

const byId = (id) => document.getElementById(id);
const status = byId("activation-status");
const signIn = byId("pilot-sign-in");
const workshop = byId("workshop");
const operator = byId("operator");
const kitsList = byId("my-kits");
const retry = byId("retry-pilot");
const boardSetup = byId("board-setup");
const boardStatus = byId("board-status");
const dashboard = byId("local-dashboard");
const workshopStatus = byId("workshop-status");
const wifiForm = byId("configure-board-wifi");
const registrationForm = byId("prepare-board");
const encoder = new TextEncoder();
const kitIdPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const macPattern = /^[a-f0-9]{12}$/;
let busy = false;
let pendingRegistration;
let selectedBoard;
let accountId;

function createText(tag, text) {
    const element = document.createElement(tag);
    element.textContent = text;
    return element;
}

function formatMac(mac) {
    return mac.toUpperCase().match(/.{2}/g).join(":");
}

function enteredMac() {
    return byId("board-mac").value.trim().toLowerCase().replaceAll(/[:-]/g, "");
}

function kitButton(text, handler) {
    const button = createText("button", text);
    button.type = "button";
    button.className = "pilot-button secondary";
    button.addEventListener("click", handler);
    return button;
}

function hex(bytes) {
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function sha256(value) {
    return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))));
}

async function runAction(task, feedback = status) {
    if (busy) return;
    busy = true;
    const buttons = [...document.querySelectorAll("main button:not(:disabled)")];
    buttons.forEach((button) => { button.disabled = true; });
    try {
        await task();
    } catch {
        feedback.textContent = "The pilot service could not complete this request. Please try again.";
    } finally {
        busy = false;
        buttons.forEach((button) => { button.disabled = false; });
    }
}

function clearDashboard() {
    dashboard.hidden = true;
    dashboard.removeAttribute("href");
}

function selectBoard(mac, source) {
    if (selectedBoard?.mac !== mac) wifiForm.reset();
    selectedBoard = { mac, source };
    clearDashboard();
    boardSetup.hidden = false;
    byId("setup-board").textContent = "Selected ESP32 " + formatMac(mac);
    boardStatus.textContent = "Wi-Fi details go directly to the board over USB.";
    byId("board-setup-title").focus();
}

function renderKits(rows) {
    if (selectedBoard?.source === "kit" && !rows.some((kit) =>
        [kit.active_mac, kit.assigned_mac].includes(selectedBoard.mac))) {
        selectedBoard = undefined;
        boardSetup.hidden = true;
        wifiForm.reset();
        clearDashboard();
    }
    kitsList.replaceChildren();
    if (!rows.length) {
        kitsList.append(createText("p", "Claim a workshop code to see your kit here."));
        return;
    }

    for (const kit of rows) {
        const card = document.createElement("section");
        card.className = "owner-kit";
        card.append(createText("h3", "PisoPhone only · " + kit.slot_limit + " licensed slots"));
        const id = createText("p", "Kit ID ");
        id.className = "kit-label";
        id.append(createText("code", kit.id));
        card.append(id);
        const actions = document.createElement("div");
        actions.className = "pilot-actions";
        if (kit.active_mac) {
            card.append(createText("p", "Activation accepted for ESP32 " + formatMac(kit.active_mac) + "."));
            actions.append(kitButton("Connect or check current ESP32", () => selectBoard(kit.active_mac, "kit")));
        }
        if (kit.assigned_mac) {
            const replacement = kit.assigned_revision > 1;
            const board = formatMac(kit.assigned_mac);
            card.append(createText("p", kit.activation_requested
                ? `Waiting for ${replacement ? "replacement " : ""}ESP32 ${board} to check in.`
                : `Replacement ESP32 ${board} is assigned and ready for approval.`));
            if (replacement) card.append(createText("p", "Replacement boards require local phone enrollment with the current app."));
            if (replacement && !kit.activation_requested) {
                actions.append(kitButton("Activate replacement", () => runAction(async () => {
                    if (!window.confirm("Activate the assigned replacement? The current ESP32 will be retired when the replacement checks in and accepts its license.")) return;
                    const { data, error } = await supabase.rpc("pilot_request_replacement", { p_kit: kit.id });
                    if (error) throw error;
                    status.textContent = data ? "Replacement approved. Connect it to shop Wi-Fi." : "There is no replacement waiting for activation.";
                    await refreshKits();
                })));
            }
            actions.append(kitButton(replacement ? "Connect or check replacement" : "Connect or check ESP32",
                () => selectBoard(kit.assigned_mac, "kit")));
        } else if (kit.active_mac) {
            card.append(createText("p", "For a replacement, give the workshop this kit ID."));
        }
        card.append(actions);
        kitsList.append(card);
    }
}

async function refreshKits() {
    const { data, error } = await supabase.rpc("pilot_my_kits");
    if (error) throw error;
    renderKits(data);
}

byId("claim-kit").addEventListener("submit", (event) => {
    event.preventDefault();
    runAction(async () => {
        const codeInput = byId("workshop-code");
        const code = codeInput.value.trim().toLowerCase();
        if (!/^[a-f0-9]{32}$/.test(code)) {
            status.textContent = "Enter the 32-character claim code from your workshop kit card.";
            return;
        }
        const { data, error } = await supabase.rpc("pilot_claim_kit", { p_claim_code: code });
        if (error) throw error;
        if (!data) {
            status.textContent = "This workshop code is unavailable.";
            return;
        }
        codeInput.value = "";
        await refreshKits();
        status.textContent = "Kit claimed. Choose its ESP32 under My kits to continue.";
    });
});

byId("reload-kits").addEventListener("click", () => runAction(async () => {
    await refreshKits();
    status.textContent = "Kit status refreshed.";
}));

registrationForm.addEventListener("submit", (event) => {
    event.preventDefault();
    runAction(async () => {
        const mac = enteredMac();
        const kitId = byId("replacement-kit").value.trim().toLowerCase();
        const slotLimit = Number(byId("slot-limit").value);
        if (!macPattern.test(mac) || mac === "000000000000" || (kitId && !kitIdPattern.test(kitId))) {
            workshopStatus.textContent = "Enter a valid board MAC address and, for replacements, the kit ID.";
            return;
        }
        const registrationKey = [mac, kitId, slotLimit].join("|");
        if (pendingRegistration?.key !== registrationKey) {
            byId("workshop-result").hidden = true;
            byId("card-code").textContent = "";
            byId("board-credential").textContent = "";
            pendingRegistration = {
                key: registrationKey, mac, credential: hex(crypto.getRandomValues(new Uint8Array(32))),
                code: kitId ? null : hex(crypto.getRandomValues(new Uint8Array(16)))
            };
        }

        const { credential, code } = pendingRegistration;
        const digest = await sha256(credential);
        const { data, error } = kitId
            ? await supabase.rpc("pilot_assign_replacement", { p_kit: kitId, p_mac: mac, p_device_sha: digest })
            : await supabase.rpc("pilot_register_kit", {
                p_claim_sha: await sha256(code), p_mac: mac, p_device_sha: digest, p_slot_limit: slotLimit
            });
        if (error) {
            workshopStatus.textContent = error.code === "23505" ? "The board is already registered or the kit already has a board waiting to activate." :
                error.code === "22023" ? "Use the kit ID of a claimed kit." :
                    "Workshop registration could not be confirmed. Retry with the same details.";
            return;
        }
        byId("card-code-row").hidden = !code;
        byId("card-code").textContent = code ?? "";
        byId("board-credential").textContent = credential;
        byId("registered-id").textContent = kitId || data;
        workshopStatus.textContent = kitId ? "Replacement registered. The kit owner can now activate it." : "Kit registered. Save its credential over USB.";
        registrationForm.hidden = true;
        byId("workshop-result").hidden = false;
    }, workshopStatus);
});

byId("setup-workshop-board").addEventListener("click", () => {
    const mac = enteredMac();
    if (!macPattern.test(mac) || mac === "000000000000") {
        workshopStatus.textContent = "Enter the workshop ESP32 MAC address.";
        return;
    }
    selectBoard(mac, "workshop");
});

byId("setup-registered-board").addEventListener("click", () => selectBoard(pendingRegistration.mac, "workshop"));

wifiForm.addEventListener("submit", (event) => {
    event.preventDefault();
    runAction(async () => {
        if (!selectedBoard) return;
        clearDashboard();
        const name = byId("pilot-wifi-name");
        const password = byId("pilot-wifi-password");
        try {
            const command = pilotWifiCommand(name.value, password.value);
            const result = await sendToWorkshopBoard(selectedBoard.mac, command, "PISO-PILOT-WIFI-");
            if (result.endsWith(" busy")) throw new Error("The ESP32 is handling a payment. Wait for it to finish before changing Wi-Fi.");
            if (result !== "PISO-PILOT-WIFI-OK") throw new Error("The ESP32 could not save the Wi-Fi settings.");
            boardStatus.textContent = "Wi-Fi saved. After the ESP32 restarts, choose Check ESP32 over USB to see whether it joined.";
            password.value = "";
        } catch (error) {
            boardStatus.textContent = error instanceof Error ? error.message : "USB Wi-Fi setup failed.";
        }
    }, boardStatus);
});

byId("read-board-status").addEventListener("click", () => runAction(async () => {
    if (!selectedBoard) return;
    clearDashboard();
    try {
        const { ip, state, slots } = await readWorkshopBoard(selectedBoard.mac);
        const network = ip ? `Connected to Wi-Fi at ${ip}.` : "No Wi-Fi address yet. Check the network settings and try again.";
        const license = state === "active" ? `The ESP32 has saved its license for ${slots} slots.` :
            state === "retired" ? "This ESP32 has been retired." : "The ESP32 is still waiting for its license.";
        const next = ip && state === "active"
            ? " Open the local dashboard on the same Wi-Fi. In Tools → Phone slots, use Set up a phone for enrollment and check that the paired phone comes online."
            : "";
        boardStatus.textContent = `${network} ${license}${next}`;
        if (ip && state === "active") {
            dashboard.href = "http://" + ip + "/";
            dashboard.hidden = false;
        }
    } catch (error) {
        boardStatus.textContent = error instanceof Error ? error.message : "Could not read the ESP32 over USB.";
    }
}, boardStatus));

byId("save-board-credential").addEventListener("click", () => runAction(async () => {
    if (!pendingRegistration || byId("workshop-result").hidden) return;
    try {
        const { mac, credential } = pendingRegistration;
        const result = await sendToWorkshopBoard(mac, "PISO-PILOT-CREDENTIAL " + credential, "PISO-PILOT-CREDENTIAL-");
        if (result !== "PISO-PILOT-CREDENTIAL-OK") throw new Error("The ESP32 refused the new credential. Check whether it was already provisioned.");
        pendingRegistration.credentialSaved = true;
        workshopStatus.textContent = "Credential saved on the assigned ESP32.";
    } catch (error) {
        workshopStatus.textContent = error instanceof Error ? error.message : "USB credential provisioning failed.";
    }
}, workshopStatus));

byId("clear-workshop").addEventListener("click", () => {
    if (!pendingRegistration?.credentialSaved && !window.confirm("Clear these credentials before saving the board credential over USB? It cannot be recovered from the website.")) return;
    pendingRegistration = undefined;
    byId("card-code").textContent = "";
    byId("board-credential").textContent = "";
    byId("workshop-result").hidden = true;
    workshopStatus.textContent = "Credentials cleared.";
    registrationForm.reset();
    registrationForm.hidden = false;
});

async function loadPilot() {
    if (location.hostname !== "pisophone-accounts-test.pages.dev") {
        status.textContent = "Workshop activation is available on the PisoPhone beta site.";
        retry.hidden = true;
        signIn.href = "https://pisophone-accounts-test.pages.dev/account/pilot/activate/";
        signIn.textContent = "Open the PisoPhone beta";
        signIn.hidden = false;
        return;
    }
    const { data, error } = await supabase.auth.getSession();
    if (error) throw error;
    accountId = data.session?.user.id ?? null;
    if (!accountId) {
        status.textContent = "Sign in to claim or manage a kit.";
        retry.hidden = true;
        signIn.hidden = false;
        return;
    }
    operator.hidden = false;
    retry.hidden = false;
    await refreshKits();
    const seller = await supabase.rpc("store_is_seller");
    if (seller.error) throw seller.error;
    workshop.hidden = seller.data !== true;
    retry.hidden = true;
    status.textContent = "";
}

retry.addEventListener("click", () => runAction(loadPilot));
runAction(async () => {
    retry.hidden = false;
    await loadPilot();
});
supabase.auth.onAuthStateChange((_event, session) => {
    if (accountId !== undefined && (session?.user.id ?? null) !== accountId) location.reload();
});
