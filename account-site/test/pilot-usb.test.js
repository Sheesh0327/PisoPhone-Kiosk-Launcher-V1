import { test } from "node:test";
import assert from "node:assert/strict";
import { pilotWifiCommand, readWorkshopBoard, sendToWorkshopBoard } from "../pages/pilot/activate/workshop-usb.js";

test("workshop Wi-Fi settings stay in the USB command and require a matching pilot board", async (t) => {
    const command = pilotWifiCommand("Workshop", "test-password");
    assert.equal(command, "PISO-PILOT-WIFI 576f726b73686f70 746573742d70617373776f7264");
    assert.throws(() => pilotWifiCommand("a".repeat(33), "test-password"), /32 bytes/);
    assert.throws(() => pilotWifiCommand("Workshop", "short"), /8 to 63/);
    assert.equal(pilotWifiCommand("Workshop", ""), "PISO-PILOT-WIFI 576f726b73686f70 -");

    const writes = [];
    let mac = "aabbccddee01";
    let address = "10.0.0.228";
    let license = "PISO-PILOT state=awaiting_license slots=0";
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
                                const result = input === "PISO-HELLO" ? `PISO-BOX fw=test mac=${mac} setup=done wifi=up ip=${address} why=none` :
                                    input === "PISO-PILOT-STATUS" ? license :
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

    license = "PISO-PILOT state=active slots=2";
    assert.deepEqual(await readWorkshopBoard("aabbccddee02"), { ip: "10.0.0.228", state: "active", slots: 2 });
    assert.deepEqual(writes.slice(4), ["PISO-HELLO", "PISO-PILOT-STATUS"]);
    address = "10.0.0.999";
    assert.deepEqual(await readWorkshopBoard("aabbccddee02"), { ip: null, state: "active", slots: 2 });
});
