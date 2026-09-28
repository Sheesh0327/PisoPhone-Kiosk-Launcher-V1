# PisoPhone Protocol Contract (PISOPHONE-PROTOCOL-V1)

## 1. Specification Overview & Versioning

- **Protocol Version Identifier:** `PISOPHONE-PROTOCOL-V1`
- **Scope:** Defines the complete wire protocol, cryptographic authentication contract, framing, message limits, integer ranges, and side effects between the **ESP32 Master Controller** and **Android PisoPhone Kiosk Terminals** (as well as virtual controller clients).
- **Versioning Policy:**
  - Incompatible wire format changes require incrementing the protocol version identifier.
  - Both Android app and ESP32 firmware are built and distributed as **matched pairs**.
  - **No unsigned fallback:** All state-modifying requests (adding time, deducting time, changing configuration, triggering administrative actions, and credit coin pulses) strictly require cryptographic authentication. Unauthenticated requests to protected endpoints are rejected with `401 Unauthorized` or `403 Forbidden`.
  - Upgrades preserve access to recovery endpoints (`/emergency_adb`, `/recovery`) with valid cryptographic signatures so an interrupted upgrade or temporary failure does not brick field kiosks.

---

## 2. Cryptographic Contract & Framing Rules

### 2.1 Cryptographic Primitives
1. **HMAC-SHA256:**
   - Digest algorithm: HMAC-SHA256 (RFC 2104).
   - Secret key: Shared ASCII/UTF-8 string (minimum 16 bytes, default 32+ bytes), configured during kiosk provisioning / slot pairing.
   - Output representation: 64 lowercase hexadecimal characters (`%02x`).
   - Verification: Constant-time comparison to prevent timing attacks. Case-insensitive hexadecimal comparison.
2. **AES-256-CBC Payload Encryption:**
   - Cipher: AES-256 in CBC mode with PKCS#7 padding.
   - Key derivation: SHA-256 hash of the shared secret string produces the 256-bit symmetric key.
   - Initialization Vector (IV): Fixed 16-byte zero IV (`0x00 * 16`) or prepended IV.
   - Wire format: Hexadecimal-encoded ciphertext string (lowercase).

### 2.2 UTF-8 Byte-Length Prefix Framing

To prevent length-mismatch vulnerabilities and multi-byte character divergence between byte-oriented C++ (`strlen` on UTF-8) and character-oriented Kotlin/JVM (`CharSequence.length` counting UTF-16 code units):

> **Framing Rule:** Every string field passed into signature calculation MUST be framed as:
> `[UTF-8 Byte Count in ASCII Base-10] + ":" + [Field UTF-8 Bytes]`

- **Non-ASCII Handling:** If a field contains multi-byte UTF-8 characters (e.g., currency symbol `"₱"` which is 3 UTF-8 bytes `E2 82 B1`), the prefix is `"3:₱"`, NOT `"1:₱"`.
- **Empty Field Handling:** An empty string (`""`) is framed as `"0:"`.
- **Numeric Fields:** Numbers are converted to their standard decimal ASCII string representation before byte-length prefixing (e.g., `100` becomes `"3:100"`, `-60` becomes `"3:-60"`).

### 2.3 Signature Framing Formats

1. **`HTTP_REQ` Signature (Outbound HTTP from ESP32 to Android Kiosk):**
   ```
   DataToSign = lengthPrefixed("HTTP_REQ") +
                lengthPrefixed(method) +
                lengthPrefixed(endpoint) +
                lengthPrefixed(recipient) +
                lengthPrefixed(tx_id) +
                lengthPrefixed(ts) +
                lengthPrefixed(payload)
   Signature = HMAC_SHA256(DataToSign, Secret)
   ```

2. **`WS_PAY` Signature (WebSocket Coin Delivery from ESP32 to Android Kiosk):**
   ```
   DataToSign = lengthPrefixed("WS_PAY") +
                lengthPrefixed(event) +
                lengthPrefixed(recipient) +
                lengthPrefixed(tx_id) +
                lengthPrefixed(ts) +
                lengthPrefixed(payload)
   Signature = HMAC_SHA256(DataToSign, Secret)
   ```

3. **`ACK` Signature (Durable Acknowledgment from Android Kiosk to ESP32):**
   ```
   DataToSign = lengthPrefixed("ACK") +
                lengthPrefixed(device_id) +
                lengthPrefixed(tx_id) +
                lengthPrefixed(amountStr) +
                lengthPrefixed(secondsStr) +
                lengthPrefixed(ts) +
                lengthPrefixed(status)
   Signature = HMAC_SHA256(DataToSign, Secret)
   ```

4. **Telemetry & Handshake Signature (`TELEMETRY_SIG` / `TIMESTAMP_SIG`):**
   ```
   DataToSign = deviceId + ":" + ts
   Signature = HMAC_SHA256(DataToSign, Secret)
   ```

5. **Discovery Signature (`DISCOVERY_SIG`):**
   ```
   DataToSign = "DISCOVERY:" + macAddress + ":" + ipAddress
   Signature = HMAC_SHA256(DataToSign, Secret)
   ```

---

## 3. Protocol Limits, Ranges, and Constraints

| Parameter | Type / Encoding | Valid Range / Limit | Constraints & Rules |
| :--- | :--- | :--- | :--- |
| `device_id` | UTF-8 String | 1 to 64 bytes | Terminal hardware identifier. Case-insensitive comparison. |
| `session_id` | UTF-8 String | 1 to 64 bytes | Virtual controller session identifier. Isolated from device slots. |
| `tx_id` | ASCII String | 1 to 64 chars | Unique transaction identifier. Exact case-sensitive match. |
| `ts` | ASCII String / uint64 | Epoch milliseconds | Timestamp for replay protection. Window: ±60,000 ms (HTTP/WS), ±300,000 ms (Controller). Must be monotonic per sender. |
| `amount` | Integer (pulses) | 0 to 1,000,000 | Number of coin pulses (₱1 per pulse). Non-negative. Must be 0 for manual time deductions. |
| `seconds` | Signed 32-bit Integer | -2,147,483,648 to 2,147,483,647 | Net seconds adjusted. Positive = addition, Negative = deduction. |
| `minutes` | Integer | 0 to 35,791,394 | Time in minutes (derived from pulses * rate, or quick adjust). |
| `price` | Double | 0.0 to 10,000.0 | Price per coin block in PHP currency. |
| `status` | ASCII String | `"OK"`, `"ALREADY_PROCESSED"` | Uppercase exact match for valid ACKs. |
| HTTP Body | Raw bytes | Max 8,192 bytes | Protects against buffer overflow on embedded servers. |
| WS Text Frame | UTF-8 String | Max 2,048 bytes | RFC 6455 text frame limit on ESP32 socket buffer. |
| UDP Packet | Raw bytes | Max 512 bytes | Fits within standard Ethernet MTU without fragmentation. |

---

## 4. End-to-End Inventory of Routes, Transports & Events

### 4.1 Android Kiosk Endpoints (Port 8080 - `KioskHttpServer`)

| Endpoint | Method | Sender | Receiver | Auth Role | Payload / Params | Side Effect | Response |
| :--- | :---: | :---: | :---: | :---: | :--- | :--- | :--- |
| `/heartbeat`, `/ping` | GET | ESP32 / Tool | Kiosk | Public | None | Updates last heartbeat timestamp. | `200 OK` (`"OK"`) |
| `/identify`, `/status` | GET | ESP32 / Tool | Kiosk | Public | None | Telemetry probe during discovery. | `200 OK` (JSON status) |
| `/challenge` | GET | ESP32 | Kiosk | Public | None | Probing availability. | `200 OK` (`"OK"`) |
| `/get_time` | GET | ESP32 / Tool | Kiosk | Public | None | Read-only remaining session seconds. | `200 OK` (Remaining seconds) |
| `/state` | GET | ESP32 / Tool | Kiosk | Public | None | Read-only application state integer. | `200 OK` (State int) |
| `/audit` | GET | ESP32 / Tool | Kiosk | Public | None | Read-only audit log events. | `200 OK` (JSON array) |
| `/crash` | GET | ESP32 / Tool | Kiosk | Public | None | Read-only crash diagnostics. | `200 OK` (Crash log) or `404` |
| `/add_time`, `/coin` | GET | ESP32 Auth Worker | Kiosk | **Protected & Signed** | Outer: `payload`, `hmac`, `device_id`, `tx_id`, `ts`. Decrypted: `seconds`, `amount`, `tx_id`, `device_id`, `ts`. | Credits or deducts session time. Enforces idempotency via Room DB `payments` table. | `200 OK` with signed ACK string; or `409 Conflict`, `403 Forbidden`, `503 Unavailable`. |
| `/config` | GET | ESP32 Admin | Kiosk | **Protected & Signed** | Outer: `payload`, `hmac`, `device_id`, `tx_id`, `ts`. Decrypted: `price`, `minutes`, `device_name`, `admin_pin`, `slot`. | Updates pricing, name, admin PIN, slot assignment. | `200 OK` (`"OK"`) |
| `/trigger_action` | GET | ESP32 Admin | Kiosk | **Protected & Signed** | Outer: `payload`, `hmac`, `device_id`, `tx_id`, `ts`. Decrypted: `action`, `slot`. | Triggers kiosk action (lock, unlock, reboot). | `200 OK` (`"OK"`) |
| `/emergency_adb` | GET | ESP32 Admin | Kiosk | **Protected & Signed** | Outer: `payload`, `hmac`, `device_id`, `tx_id`, `ts`. | Enables ADB debugging for emergency recovery. | `200 OK` (`"RECOVERY_TRIGGERED"`) |
| `/recovery` | GET | ESP32 Admin | Kiosk | **Protected & Signed** | Outer: `payload`, `hmac`, `device_id`, `tx_id`, `ts`. | Initiates system recovery procedure. | `200 OK` (`"RECOVERY_TRIGGERED"`) |

### 4.2 ESP32 Master Endpoints (Port 80 - `WebServerModule`)

| Endpoint | Method | Sender | Receiver | Auth Role | Payload / Params | Side Effect | Response |
| :--- | :---: | :---: | :---: | :---: | :--- | :--- | :--- |
| `/api/heartbeat` | GET | Kiosk App | ESP32 | Telemetry Auth | `device_id`, `ts`, `sig`, `ip`, `slot`, `time_left`, `state`, `battery_pct`, `charging`, `app_version`. | Updates device tracking table, battery, slot status, clock sync. | `200 OK` (JSON config + slot status + admin PIN). |
| `/add_time` | POST | Web Dashboard | ESP32 | Admin Auth | `target_ip`, `add_minutes`, `adjust_action`. | Queues authenticated `/add_time` request to Kiosk on port 8080. | `303 See Other` redirect to dashboard. |
| `/api/devices` | GET | Web Dashboard | ESP32 | Admin Auth | None | Read-only list of tracked devices and slot assignments. | `200 OK` (JSON array). |
| `/api/coinslot/status` | GET | Web Dashboard | ESP32 | Admin Auth | None | Read-only coin slot arming and hardware mutex state. | `200 OK` (JSON status). |

### 4.3 WebSocket Terminal Interface (Port 81, `/ws` - `WebSocketsUdp`)

1. **Handshake (Client -> ESP32):**
   - URI: `GET /ws?device_id=<id>&ts=<ts>&sig=<sig>`
   - Protocol: RFC 6455 Upgrade.
   - Validation:
     - `sig == HMAC(device_id + ":" + ts, secret)`
     - Replay protection: `ts` is fresh and monotonic.
     - Device is assigned to an active, licensed slot (`isSlotActive`).
     - Coin slot mutex claim: `tryClaimCoinSlotForArming(device_id, PHONE, 5000)`.
   - Rejection Responses:
     - `400 Bad Request`: Missing query parameters.
     - `403 Forbidden`: Invalid signature or replay detected.
     - `423 Locked`: Device not paired to any slot or slot expired.
     - `409 Conflict`: Coin slot busy with another device.
   - Success Response: `101 Switching Protocols` -> Connection upgraded.

2. **Server -> Client Events:**
   - `{"event":"ARMED"}`: Confirms coin slot is armed and listening for pulses.
   - `{"event":"TIMEOUT"}`: Arming TTL (30s) expired without coin insertion.
   - `{"event":"COIN_DETECTED","device_id":"<id>","payload":"<hex>","seconds":N,"amount":P,"tx_id":"<tx>","ts":"<ts>","v_sig":"<sig>"}`:
     - Outer envelope exposes signed context: `device_id`, `tx_id`, `ts`, `seconds`, `amount`, `payload`, `v_sig`.
     - Inner encrypted payload (AES-256-CBC) contains:
       `{"seconds":N,"minutes":M,"amount":P,"tx_id":"<tx>","ts":"<ts>","device_id":"<id>"}`

3. **Client -> Server Messages:**
   - `{"event":"ACK","device_id":"<id>","tx_id":"<tx>","amount":P,"seconds":N,"ts":"<ts>","v_sig":"<sig>","status":"OK"|"ALREADY_PROCESSED"}`:
     - Client signs ACK over `ACK` | `device_id` | `tx_id` | `amount` | `seconds` | `ts` | `status`.
     - ESP32 verifies signature and marks payment durably acknowledged (`acknowledgePhonePayment`), removing it from pending NVS retry queue.
   - `"DONE"` or `"CLOSE"`: Clean terminal session release when user finishes adding coins.

### 4.4 Controller WebSocket Interface (Port 81, `/coinslot` or `/ws/coinslot`)

- **Handshake:** `GET /ws/coinslot?session_id=<id>&ts=<ts>&sig=<sig>`
- **Authentication:** HMAC with `sharedSecret` or `MASTER_CRYPTO_SECRET`. Replay window: ±300,000 ms.
- **Isolation:** Completely separated from phone device tracking and slot licensing. No phone pricing rules are applied.
- **Events:**
  - `{"event":"ARMED","session_id":"<id>"}`
  - `{"event":"COIN_DETECTED","session_id":"<id>","pulses":P,"tx_id":"<tx>"}`
  - `{"event":"SESSION_ENDED","session_id":"<id>","reason":"<reason>"}`
- **ACK:** `{"event":"ACK","session_id":"<id>","tx_id":"<tx>"}`

### 4.5 UDP Discovery (Port 8888)

- **Probe (Client Broadcast/Unicast):** Packet containing `"PISOPHONE_DISCOVER"` (optionally `"target_mac": "..."`).
- **Response (ESP32 Unicast & Broadcast):**
  `{"type":"PISOPHONE_ESP32_RESPONSE","device":"PISOPHONE_MASTER","mac":"...","ip":"...","sig":"...","port":80,"ws_port":81,"device_name":"...","slots":5,"minutes":30,"price":1.0,"uptime":...}`
- **Security Rule:** **Discovery NEVER supplies trusted credentials.** Discovery only advertises DHCP network presence and box metadata. Encryption and signing keys must be provisioned independently or configured out-of-band.

---

## 5. Integrity-Before-Decryption Invariant

1. **Protocol Requirement:** A receiver MUST verify the integrity of the signed envelope before attempting any cryptographic decryption of the payload.
2. **Execution Steps:**
   - **Step 1 (Envelope Extraction):** Extract outer metadata (`recipient`/`device_id`, `tx_id`, `ts`, `payload`, `v_sig`).
   - **Step 2 (Integrity Verification):** Compute expected HMAC using the shared secret and outer fields. Verify that `v_sig` matches expected HMAC in constant time. If invalid, reject immediately.
   - **Step 3 (Replay & Recipient Check):** Verify that `recipient` matches the local hardware device ID, and `ts` is within the valid timestamp skew window (±60s).
   - **Step 4 (Decryption):** Decrypt `payload` using AES-256-CBC with the shared key. If decryption fails or output is empty, reject with decryption failure.
   - **Step 5 (Context Reconciliation):** Parse decrypted JSON/query parameters. Verify that every context field (`tx_id`, `device_id`, `ts`, `seconds`, `amount`) inside the decrypted payload EXACTLY matches the envelope context. Any mutation causes immediate rejection with `MUTATED_PAYLOAD_CONTEXT`.
   - **Step 6 (State Mutation & ACK):** Apply payment to database / balance engine, and return signed ACK.
