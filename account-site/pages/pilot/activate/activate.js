import { supabase } from "../../auth.js";
import { pilotWifiCommand, sendToWorkshopBoard } from "./workshop-usb.js";

const byId = (id) => document.getElementById(id);
const status = byId("activation-status");
const signIn = byId("pilot-sign-in");
const workshop = byId("workshop");
const operator = byId("operator");
const kitsList = byId("my-kits");
const encoder = new TextEncoder();
const kitIdPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
let busy = false;
let pendingRegistration;

function setStatus(message) {
    status.textContent = message;
}

function createText(tag, text) {
    const element = document.createElement(tag);
    element.textContent = text;
    return element;
}

function hex(bytes) {
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function sha256(value) {
    return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))));
}

async function runAction(task) {
    if (busy) return;
    busy = true;
    try {
        await task();
    } catch {
        setStatus("The pilot service could not complete this request. Please try again.");
    } finally {
        busy = false;
    }
}

function renderKits(rows) {
    kitsList.replaceChildren();
    if (!rows.length) {
        kitsList.append(createText("p", "Claim a workshop code to see your kit here."));
        return;
    }

    for (const kit of rows) {
        const card = document.createElement("section");
        card.className = "owner-kit";
        card.append(createText("h3", "PisoPhone only"));
        card.append(createText("p", kit.id + " · " + kit.slot_limit + " licensed slots"));
        if (kit.active_mac) {
            card.append(createText("p", "License saved by ESP32 " + kit.active_mac.toUpperCase() + "."));
        }
        if (kit.assigned_mac && kit.activation_requested) {
            card.append(createText("p", "Waiting for assigned ESP32 " + kit.assigned_mac.toUpperCase() + " to save its license."));
        } else if (kit.assigned_mac) {
            card.append(createText("p", "Assigned replacement ESP32 " + kit.assigned_mac.toUpperCase() + " is ready to activate."));
            const action = createText("button", "Activate replacement");
            action.type = "button";
            action.className = "pilot-button secondary";
            action.addEventListener("click", () => runAction(async () => {
                const { data, error } = await supabase.rpc("pilot_request_replacement", { p_kit: kit.id });
                if (error) throw error;
                await refreshKits();
                setStatus(data ? "Connect the assigned replacement on shop Wi-Fi." : "This kit has no replacement waiting for activation.");
            }));
            card.append(action);
        }
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
            setStatus("Enter the 32-character claim code from your workshop kit card.");
            return;
        }
        const { data, error } = await supabase.rpc("pilot_claim_kit", { p_claim_code: code });
        if (error) throw error;
        if (!data) {
            setStatus("This workshop code is unavailable.");
            return;
        }
        codeInput.value = "";
        await refreshKits();
        setStatus("Kit claimed. Connect the assigned ESP32 to shop Wi-Fi.");
    });
});

byId("reload-kits").addEventListener("click", () => runAction(async () => {
    await refreshKits();
    setStatus("Kit status refreshed.");
}));

byId("prepare-board").addEventListener("submit", (event) => {
    event.preventDefault();
    runAction(async () => {
        const rawMac = byId("board-mac").value.trim().toLowerCase();
        const mac = rawMac.replaceAll(/[:-]/g, "");
        const kitId = byId("replacement-kit").value.trim().toLowerCase();
        const slotLimit = Number(byId("slot-limit").value);
        if (!/^[a-f0-9]{12}$/.test(mac) || mac === "000000000000" || (kitId && !kitIdPattern.test(kitId))) {
            setStatus("Enter a valid board MAC address and, for replacements, the kit ID.");
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
            setStatus(error.code === "23505" ? "This board or kit assignment conflicts with an existing record." :
                "Workshop registration could not be confirmed. Retry with the same details.");
            return;
        }
        byId("card-code-row").hidden = !code;
        byId("card-code").textContent = code ?? "";
        byId("board-credential").textContent = credential;
        byId("registered-id").textContent = kitId || data;
        byId("workshop-status").textContent = kitId ? "Replacement registered. The kit owner can now activate it." : "Kit registered.";
        byId("workshop-result").hidden = false;
        setStatus("Board registration saved.");
    });
});

const usbStatus = byId("workshop-usb-status");

byId("configure-board-wifi").addEventListener("submit", (event) => {
    event.preventDefault();
    runAction(async () => {
        const mac = byId("board-mac").value.trim().toLowerCase().replaceAll(/[:-]/g, "");
        const name = byId("pilot-wifi-name");
        const password = byId("pilot-wifi-password");
        try {
            const command = pilotWifiCommand(name.value, password.value);
            const result = await sendToWorkshopBoard(mac, command, "PISO-PILOT-WIFI-");
            if (result !== "PISO-PILOT-WIFI-OK") throw new Error("The ESP32 could not save the Wi-Fi settings.");
            usbStatus.textContent = "Wi-Fi saved. The ESP32 is restarting to join the network.";
            password.value = "";
        } catch (error) {
            usbStatus.textContent = error instanceof Error ? error.message : "USB Wi-Fi setup failed.";
        }
    });
});

byId("save-board-credential").addEventListener("click", () => runAction(async () => {
    if (!pendingRegistration || byId("workshop-result").hidden) return;
    try {
        const { mac, credential } = pendingRegistration;
        const result = await sendToWorkshopBoard(mac, "PISO-PILOT-CREDENTIAL " + credential, "PISO-PILOT-CREDENTIAL-");
        if (result !== "PISO-PILOT-CREDENTIAL-OK") throw new Error("The ESP32 refused the new credential. Check whether it was already provisioned.");
        usbStatus.textContent = "Credential saved on the assigned board.";
    } catch (error) {
        usbStatus.textContent = error instanceof Error ? error.message : "USB credential provisioning failed.";
    }
}));

byId("clear-workshop").addEventListener("click", () => {
    pendingRegistration = undefined;
    byId("card-code").textContent = "";
    byId("board-credential").textContent = "";
    byId("workshop-result").hidden = true;
    byId("prepare-board").reset();
});

signIn.addEventListener("click", () => sessionStorage.setItem("pisophone-preview-pilot-return", "1"));
runAction(async () => {
    if (location.hostname !== "pisophone-accounts-test.pages.dev") {
        setStatus("Workshop activation is available on the PisoPhone beta site.");
        return;
    }
    const { data, error } = await supabase.auth.getSession();
    if (error) throw error;
    if (!data.session) {
        setStatus("Sign in to see your kits.");
        signIn.hidden = false;
        return;
    }
    operator.hidden = false;
    await refreshKits();
    const seller = await supabase.rpc("store_is_seller");
    workshop.hidden = seller.data !== true;
    setStatus("");
});
