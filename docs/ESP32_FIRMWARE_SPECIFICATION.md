# ESP32 Master Box Firmware & Interface Specification

## 1. Executive Summary & Overview
The ESP32 Master Box serves as the hardware controller and security anchor for the PisoPhone Kiosk system. It manages coin acceptor hardware pulses, slot activation states, device telemetry aggregation, local network candidate discovery, and WebSocket coin distribution to active Android kiosk client devices.

---

## 2. Network Discovery Architecture
The ESP32 Master Box and Android Kiosk discover each other over local Wi-Fi without requiring manual IP entry.

### 2.1 Multi-Layer Discovery Mechanisms
1. **SSDP / Multicast Listener**:
   - Multicast Group: `239.255.255.250`, Port `1900`.
   - ESP32 responds with device payload containing IP and assigned slot details.
2. **UDP Broadcast Listener**:
   - Port: `8888` / `8889`.
   - ESP32 broadcasts presence frames containing device MAC address, current IP, and assigned slot configuration.
3. **Subnet IP Probing**:
   - Fallback HTTP ping probe to `/api/ping` or `/api/status` on port `80` across local `/24` subnet candidates.

---

## 3. HTTP Telemetry & Heartbeat API

### 3.1 Endpoint: `POST /api/heartbeat`
Every 5 seconds (or configured interval), the Android device sends a telemetry heartbeat to the ESP32 Master Box.

#### Request Body (JSON):
```json
{
  "device_id": "ANDROID_UNIQUE_ID",
  "app_state": 1,
  "battery_level": 95,
  "is_charging": true,
  "session_remaining": 1200,
  "timestamp": 1726388800,
  "signature": "HMAC_SHA256_HEX_STRING"
}
```

#### Signature Verification:
The signature is generated using HMAC-SHA256 over:
`device_id + ":" + timestamp` using the shared provisioning secret key.

#### Response Body (JSON):
```json
{
  "status": "OK",
  "slot_number": 1,
  "price_per_coin": 1.0,
  "minutes_per_coin": 10,
  "admin_pin": "1234",
  "slot_status": "ACTIVE",
  "days_remaining": 30,
  "warning_message": ""
}
```

#### Status Directives:
- **`ACTIVE`**: Slot is operational.
- **`WARNING`**: Slot expires soon; warning payload included (`days_remaining`).
- **`EXPIRED` / `LOCKDOWN`**: Slot is disabled on ESP32 portal. Android activates local lockdown mode.

---

## 4. WebSocket Arming & Coin Processing Protocol

### 4.1 Arming Sequence (`ws://<ESP32_IP>:81/ws`)
When a user clicks "Insert Coin" on the Android UI, the Android client establishes a WebSocket connection to the ESP32 Master Box.

#### Connection Parameters:
`ws://<ESP32_IP>:81/ws?device_id=<DEVICE_ID>&ts=<TIMESTAMP>&sig=<SIGNATURE>`

- **Port**: `81`
- **Security Check**: ESP32 validates signature and verifies if the target slot is currently free.

#### Connection Status Codes:
- **`101 Switching Protocols`**: Session armed successfully. Coin acceptor energized.
- **`409 Conflict`**: Slot is currently BUSY with another active session.
- **`403 / 423 Locked`**: Slot expired or unprovisioned.

### 4.2 Coin Event Payload (ESP32 -> Android)
When a physical coin is inserted into the coin selector, the ESP32 increments the coin counter and sends an encrypted/signed JSON packet over the WebSocket.

```json
{
  "event": "COIN",
  "tx_id": "tx_1726388850_slot1_0042",
  "amount": 1.0,
  "minutes": 10,
  "seconds": 600,
  "timestamp": 1726388850
}
```

### 4.3 Draining & Unarming Guard (`DONE`)
1. Upon completing session time or user exit, Android sends text frame `"DONE"` over the WebSocket.
2. ESP32 de-energizes coin acceptor relay and closes the WebSocket session (`1000 Normal Closure`).
3. Android enforces a 5000ms safety timeout drain window to receive trailing pulses before tearing down socket resources.

---

## 5. Security & Cryptographic Contract
1. **HMAC-SHA256 Verification**: All critical requests (`/api/heartbeat`, WebSocket handshakes) mandate HMAC-SHA256 signatures derived from the provisioned secret key.
2. **Replay Protection**: Timestamps outside a 300-second window are rejected by the ESP32.
3. **Transaction Idempotency**: Each coin event contains a unique `tx_id`. Android stores receipts in Room DB (`PaymentReceipt`) to guarantee single-credit processing.

---

## 6. Hardware & Coin Acceptor Logic
1. **Interrupt Pin Handler**: ESP32 captures pulse trains from multi-coin selectors (e.g., 1 pulse = ₱1, 5 pulses = ₱5, 10 pulses = ₱10).
2. **Debounce Guard**: Hardware timer debounce (50ms) filters noise.
3. **Relay Control**: Optical relay isolates power to the coin selector, powering it on ONLY during active arming windows.
