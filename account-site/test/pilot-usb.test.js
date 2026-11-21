import { test } from "node:test";
import assert from "node:assert/strict";
import { pilotWifiCommand, sendToWorkshopBoard } from "../pages/pilot/activate/workshop-usb.js";

test("workshop Wi-Fi settings stay in the USB command and require a matching pilot board", async (t) => {
    const command = pilotWifiCommand("Workshop", "test-password");
    assert.equal(command, "PISO-PILOT-WIFI 576f726b73686f70 746573742d70617373776f7264");
    assert.throws(() => pilotWifiCommand("a".repeat(33), "test-password"), /32 bytes/);
    assert.throws(() => pilotWifiCommand("Workshop", "short"), /8 to 63/);
    assert.equal(pilotWifiCommand("Workshop", ""), "PISO-PILOT-WIFI 576f726b73686f70 -");

    const writes = [];
    let mac = "aabbccddee01";
    let responses;
    Object.defineProperty(navigator, "serial", {
        configurable: true,
        value: {
            async requestPort() {
                return {
                    async open() {
                        this.readable = new ReadableStream({ start(controller) { responses = controller; } });
                        this.writable = new WritableStream({
                            write(bytes) {
                                const input = new TextDecoder().decode(bytes).trim();
                                writes.push(input);
                                const result = input === "PISO-HELLO" ? "PISO-BOX fw=test mac=" + mac :
                                    input === "PISO-PILOT-STATUS" ? "PISO-PILOT state=awaiting_license slots=0" :
                                    "PISO-PILOT-WIFI-OK";
                                responses.enqueue(new TextEncoder().encode(result + "\r\n"));
                            }
                        });
                    },
                    async close() {}
                };
            }
        }
    });
    t.after(() => { delete navigator.serial; });

    await assert.rejects(sendToWorkshopBoard("aabbccddee02", command, "PISO-PILOT-WIFI-"), /does not match/);
    assert.deepEqual(writes, ["PISO-HELLO"]);
    mac = "aa:bb:cc:dd:ee:02";
    assert.equal(await sendToWorkshopBoard("aabbccddee02", command, "PISO-PILOT-WIFI-"), "PISO-PILOT-WIFI-OK");
    assert.deepEqual(writes.slice(1), ["PISO-HELLO", "PISO-PILOT-STATUS", command]);
});
