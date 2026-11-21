const encoder = new TextEncoder();

function encodeHex(text) {
    return Array.from(encoder.encode(text), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function pilotWifiCommand(name, password) {
    const size = encoder.encode(name).length;
    const passwordSize = encoder.encode(password).length;
    if (size < 1 || size > 32 || (passwordSize > 0 && passwordSize < 8) || passwordSize > 63 || name.includes("\0") || password.includes("\0")) {
        throw new Error("Use a Wi-Fi name up to 32 bytes and leave the password blank for open Wi-Fi or use 8 to 63 bytes.");
    }
    return "PISO-PILOT-WIFI " + encodeHex(name) + " " + (password ? encodeHex(password) : "-");
}

async function withWorkshopBoard(mac, action) {
    if (!/^[0-9a-f]{12}$/.test(mac)) throw new Error("Choose an ESP32 with a valid MAC address.");
    if (!navigator.serial) throw new Error("USB setup requires desktop Chrome or Edge on the computer connected to the board.");
    let port;
    try {
        port = await navigator.serial.requestPort();
        await port.open({ baudRate: 115200 });
    } catch {
        throw new Error("The USB port could not be opened. Select the connected workshop ESP32 and close other serial tools.");
    }

    let reader;
    let writer;
    const decoder = new TextDecoder();
    let buffered = "";

    async function exchange(input, prefix) {
        const timeout = setTimeout(() => void reader.cancel().catch(() => {}), 6000);
        try {
            await writer.write(encoder.encode(input + "\n"));
            while (true) {
                const newline = buffered.indexOf("\n");
                if (newline >= 0) {
                    const line = buffered.slice(0, newline).trim();
                    buffered = buffered.slice(newline + 1);
                    if (line.startsWith(prefix)) return line;
                    continue;
                }
                const { value, done } = await reader.read();
                if (done) throw new Error("The board did not answer. Check the cable and workshop firmware.");
                buffered += decoder.decode(value, { stream: true });
                if (buffered.length > 8192) throw new Error("The serial response was unexpectedly long.");
            }
        } finally {
            clearTimeout(timeout);
        }
    }

    try {
        reader = port.readable.getReader();
        writer = port.writable.getWriter();
        const hello = await exchange("PISO-HELLO", "PISO-BOX ");
        const actual = /\bmac=([a-f0-9:]+)\b/i.exec(hello)?.[1]?.replaceAll(":", "").toLowerCase();
        if (actual !== mac) throw new Error("The connected board does not match the selected ESP32.");
        const license = await exchange("PISO-PILOT-STATUS", "PISO-PILOT state=");
        return await action({ exchange, hello, license });
    } finally {
        reader?.releaseLock();
        writer?.releaseLock();
        await port.close().catch(() => {});
    }
}

export function sendToWorkshopBoard(mac, command, responsePrefix) {
    return withWorkshopBoard(mac, ({ exchange }) => exchange(command, responsePrefix));
}

export function readWorkshopBoard(mac) {
    return withWorkshopBoard(mac, ({ hello, license }) => {
        const ip = /\bwifi=up ip=(\S+)/.exec(hello)?.[1];
        const octets = ip?.split(".");
        const hasAddress = octets?.length === 4 && octets.every((part) => /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255);
        const status = /^PISO-PILOT state=(active|awaiting_license|retired) slots=(\d{1,2})$/.exec(license);
        if (!status) throw new Error("The ESP32 returned an unknown license status.");
        return { ip: hasAddress && ip !== "0.0.0.0" ? ip : null, state: status[1], slots: Number(status[2]) };
    });
}
