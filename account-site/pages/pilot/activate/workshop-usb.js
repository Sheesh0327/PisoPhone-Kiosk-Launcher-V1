const encoder = new TextEncoder();

function encodeHex(text) {
    return Array.from(encoder.encode(text), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function pilotWifiCommand(name, password) {
    const size = encoder.encode(name).length;
    const passwordSize = encoder.encode(password).length;
    if (size < 1 || size > 32 || passwordSize < 8 || passwordSize > 63 || name.includes("\0") || password.includes("\0")) {
        throw new Error("Use a Wi-Fi name up to 32 bytes and a password between 8 and 63 bytes.");
    }
    return "PISO-PILOT-WIFI " + encodeHex(name) + " " + encodeHex(password);
}

export async function sendToWorkshopBoard(mac, command, responsePrefix) {
    if (!/^[0-9a-f]{12}$/.test(mac)) throw new Error("Enter the ESP32 MAC address in Workshop registration first.");
    if (!navigator.serial) throw new Error("USB setup requires desktop Chrome or Edge on the computer connected to the board.");
    let port;
    try {
        port = await navigator.serial.requestPort();
        await port.open({ baudRate: 115200 });
    } catch {
        throw new Error("The USB port could not be opened. Select the connected workshop ESP32 and close other serial tools.");
    }

    const reader = port.readable.getReader();
    const writer = port.writable.getWriter();
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
        const hello = await exchange("PISO-HELLO", "PISO-BOX ");
        const actual = /\bmac=([a-f0-9:]+)\b/i.exec(hello)?.[1]?.replaceAll(":", "").toLowerCase();
        if (actual !== mac) throw new Error("The connected board does not match the workshop MAC address.");
        await exchange("PISO-PILOT-STATUS", "PISO-PILOT state=");
        return await exchange(command, responsePrefix);
    } finally {
        reader.releaseLock();
        writer.releaseLock();
        await port.close().catch(() => {});
    }
}
