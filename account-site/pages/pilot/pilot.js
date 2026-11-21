const storageKey = "technet-pisophone-pilot-v1";
const claimCode = "PISO-PILOT";
const networks = ["Shop Wi-Fi", "Replacement router Wi-Fi"];
const freshKit = () => ({ stage: "unclaimed", board: 1, network: 0, sessions: 0 });
const stages = new Set(["unclaimed", "claimed", "pair", "ready", "replacement", "paused"]);

const byId = (id) => document.getElementById(id);
const claimForm = byId("claim-form");
const codeInput = byId("claim-code");
const actions = byId("actions");
const feedback = byId("feedback");

function loadKit() {
    try {
        const saved = JSON.parse(localStorage.getItem(storageKey));
        if (saved && stages.has(saved.stage) && Number.isSafeInteger(saved.board) && saved.board >= 1 &&
            networks[saved.network] && Number.isSafeInteger(saved.sessions) && saved.sessions >= 0) {
            return saved;
        }
    } catch {
        // Continue with a sample kit if browser storage is unavailable.
    }
    return freshKit();
}

let kit = loadKit();
const boardId = (number) => "ESP32-C3-" + String(number).padStart(2, "0");

function showFeedback(message, error = false) {
    feedback.hidden = !message;
    feedback.textContent = message;
    feedback.dataset.error = String(error);
}

function update(changes, message, actionLabel) {
    kit = { ...kit, ...changes };
    try {
        localStorage.setItem(storageKey, JSON.stringify(kit));
    } catch {
        message = [message, "Browser storage is unavailable. Keep this tab open to retain progress."].filter(Boolean).join(" ");
    }
    render();
    showFeedback(message);
    const nextButton = [...actions.children].find((button) => button.textContent === actionLabel);
    (nextButton ?? actions.firstElementChild ?? codeInput).focus();
}

function addAction(label, task, secondary = false) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = secondary ? "pilot-button secondary" : "pilot-button";
    button.textContent = label;
    button.addEventListener("click", task);
    actions.append(button);
}

function render() {
    const claimed = kit.stage !== "unclaimed";
    const hasGrant = kit.stage === "pair" || kit.stage === "ready";
    const ready = kit.stage === "ready";
    const replacementPending = kit.stage === "replacement" || kit.stage === "paused";

    claimForm.hidden = claimed;
    actions.replaceChildren();
    byId("active-board").textContent = hasGrant ? boardId(kit.board) : "—";
    byId("network").textContent = hasGrant ? networks[kit.network] : "—";
    byId("slots").textContent = hasGrant ? "4 (2 included + 2 purchased)" : "—";
    byId("session-count").textContent = kit.sessions;
    byId("check-claim").dataset.complete = String(claimed);
    byId("check-grant").dataset.complete = String(hasGrant);
    byId("check-phone").dataset.complete = String(ready);

    let title;
    let details;
    if (replacementPending) {
        const assigned = boardId(kit.board + 1);
        const activate = () => update(
            { stage: "pair", board: kit.board + 1 },
            assigned + " saved the kit license with all four slots. Pair the rental phone again."
        );
        if (kit.stage === "paused") {
            title = "Replacement interrupted";
            details = "The original board is offline. Resume activation on the assigned " + assigned + ".";
            addAction("Resume activation", activate);
        } else {
            title = "Activate replacement";
            details = "The original board is offline. The pre-flashed " + assigned + " is ready to connect to " + networks[kit.network] + ".";
            addAction("Activate assigned ESP32", activate);
            addAction("Simulate interruption", () => update({ stage: "paused" }, "You can reload the page and resume here."), true);
        }
    } else if (ready) {
        title = "PisoPhone is ready";
        details = "The board is licensed and the rental phone is online. Try a coin-funded session or replace the board.";
        addAction("Insert sample ₱5 coin", () => update(
            { sessions: kit.sessions + 1 }, "₱5 credited. The rental phone unlocked for the sample paid session.", "Insert sample ₱5 coin"
        ));
        addAction("Change shop router", () => {
            const network = (kit.network + 1) % networks.length;
            update({ network }, "The board and rental phone reconnected on " + networks[network] + " with the same kit license.", "Change shop router");
        }, true);
        addAction("Replace ESP32", () => update(
            { stage: "replacement" },
            boardId(kit.board) + " was disconnected. The assigned replacement can recover the kit."
        ), true);
        if (kit.board > 1) {
            addAction("Reconnect previous board", () => showFeedback(
                boardId(kit.board - 1) + " checked in and its paid operation was disabled."
            ), true);
        }
    } else if (hasGrant) {
        title = "Enroll a rental phone";
        details = "The license is saved. In the shop, use the local setup QR and confirm the rental phone comes online.";
        addAction("Simulate QR enrollment", () => update(
            { stage: "ready" }, "The rental phone paired and came online on " + networks[kit.network] + "."
        ));
    } else if (claimed) {
        title = "Waiting for the ESP32";
        details = "Your sample code was accepted. Connect the ESP32 to ordinary shop Wi-Fi to finish activation.";
        addAction("Connect sample ESP32", () => update(
            { stage: "pair" }, boardId(kit.board) + " connected and saved the kit license."
        ));
    } else {
        title = "Activate your kit";
        details = "Enter the sample code from the kit card to begin.";
    }
    byId("workflow-title").textContent = title;
    byId("workflow-details").textContent = details;
}

byId("fill-code").addEventListener("click", () => {
    codeInput.value = claimCode;
    showFeedback("");
    codeInput.focus();
});

claimForm.addEventListener("submit", (event) => {
    event.preventDefault();
    if (codeInput.value.trim().toUpperCase() !== claimCode) {
        showFeedback("Enter the sample code PISO-PILOT.", true);
        codeInput.focus();
        return;
    }
    update({ stage: "claimed" }, "Sample kit PP-0142 was claimed.");
});

byId("reset").addEventListener("click", () => {
    codeInput.value = "";
    update(freshKit(), "The sample kit was reset.");
});

render();
