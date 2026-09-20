# PisoPhone Hardening Rules & Invariants

This document establishes the inviolable core invariants, system contracts, and architectural rules for the PisoPhone kiosk ecosystem across the Android client (`app/`), ESP32 master box firmware (`esp32_firmware/`), web management interface (`website/`), and CI/CD pipelines.

Any proposed code modification must adhere strictly to these rules. Unsolicited architectural refactoring, speculative redesigns, and breaking protocol changes are prohibited.

---

## 1. Core System Invariants

### 1.1 Pricing & Revenue Accounting
- **Authoritative Pricing**: Master pricing is configured on the ESP32 Master Box (`pricePerCoin`, `minutesPerCoin`, default ₱5.00 for 30 minutes, or operator-customized via administrative portal). Android kiosks query and synchronize rates via periodic telemetry (`/api/heartbeat`) and WebSocket arming.
- **Manual Adjustment Revenue Isolation**: Manual adjustments via administrative Quick Adjust (`/add_time`) MUST always set `amount = 0.0`. Manual time injections must NEVER artificially inflate total coin revenue, shift totals, or hardware pulse accounting counters.
- **Atomic Currency Reconciliation**: Coin pulses received via physical coin acceptor hardware interrupts are buffered in thread-safe queues (`PaymentQueueManager` on ESP32) and credited atomically in the Android Room database (`PaymentRepository`) with unique transaction receipts.

### 1.2 Slot-Based Lifetime & Subscription Licensing
- **Hardware License Authority**: The ESP32 acts as the authoritative master clock and license governor for all assigned slots.
- **Slot Boundaries**: Up to 5 slots (`MAX_DEVICES = 5`). Each slot represents a physical or logical phone dock.
- **License Expiry Enforcement**:
  - `ACTIVE`: Normal kiosk operations, screen unlocked, coin insertion enabled.
  - `WARNING`: Slot expiration approaching; warning payload (`days_remaining`) displayed without disrupting active gameplay.
  - `EXPIRED` / `LOCKDOWN`: Slot is disabled on ESP32 portal. Android activates local hard lockdown mode (`SLOT LICENSE EXPIRED - HARD LOCKDOWN ACTIVE`), dismissing floating overlays and preventing coin insertion.
  - **Self-Healing License Renewal**: Locked terminals continue sending periodic heartbeats (`/api/heartbeat`). As soon as a valid slot license is applied on the ESP32, the heartbeat response reflects `"slot_expired": false`, and the Android client automatically lifts the lockdown without requiring a device reboot or application restart.

### 1.3 Five-Phone Capacity & Terminal Scaling
- **Bounded Slot Management**: Fixed maximum capacity of 5 concurrent devices (`MAX_DEVICES = 5`, slots 1 through 5).
- **IP & Slot Pinning**: The ESP32 tracks devices by unique `device_id` and assigned slot number. Outgoing administrative and coin payloads must be sent directly to the device's currently registered IP address; rerouting to alternative terminals is strictly forbidden.
- **Broadcast Isolation**: Global actions (e.g. "All Active Devices") must only dispatch to active, online terminals with verified connectivity, never broadcasting payloads to unallocated or offline slots.

### 1.4 Controller Isolation
- **1v1 Arena & Controller Separation**: Multiplayer and controller interactions operate within isolated virtual controller sessions (`ControllerWebSocket`).
- **Session Boundaries**: Controller frames from one slot/dock cannot bleed, cross-talk, or influence another slot's gameplay or telemetry.
- **Replay & Nonce Defense**: Controller WebSocket connections enforce strict millisecond timestamps (`currentMasterTs`), nonce tracking, and reject outdated or out-of-order controller frames beyond allowed network jitter tolerance (30,000 ms).

### 1.5 Quick Adjust & Match Transfer Behavior
- **Signed Administrative Endpoint**: Quick adjustments operate over `/add_time` on Android port 8080. Every request requires valid HMAC-SHA256 signature containing `device_id`, `tx_id`, `seconds`, `amount=0`, and timestamp.
- **Bidirectional Adjustments**:
  - Positive seconds (`seconds > 0`): Applied as session credits via `creditPayment()`.
  - Negative seconds (`seconds < 0`): Applied as session deductions via `deductPayment()`, clamped at 0 so session balance cannot underflow.
- **Deterministic Signed ACK Protocol**:
  - `OK`: Returned on first successful commit.
  - `ALREADY_PROCESSED`: Returned on idempotent retransmission of identical `tx_id`.
  - `HTTP 409 Conflict`: Returned if transaction payload parameters differ from existing receipt for the same `tx_id`.
  - `HTTP 403 Forbidden`: Returned if device ID or signature verification fails.
  - `HTTP 503 Service Unavailable`: Returned on internal persistence or database commit failures.
- **Match / Arena Mode Transfers**: When time is transferred during 1v1 match stakes, balance transfers must be atomic, maintaining exact session accounting on both source and target terminals.

### 1.6 Disconnect & Network Fault Accounting
- **Heartbeat Deadlines**: Device presence is tracked via `lastSeenMs`. A device is considered online if seen within 30,000 ms.
- **Telemetry Retention**: Inactive devices remain tracked for up to 300,000 ms (5 minutes) before resource cleanup, allowing transient Wi-Fi drops without dropping slot assignments.
- **Offline Session Integrity**: Active session countdown on Android continues locally during transient Wi-Fi or ESP32 disconnections. Network disconnection must NOT wipe remaining player time or lock out legitimate gameplay.

---

## 2. Session Time & Clock Architecture

### 2.1 Same-Boot Monotonic Deadlines
- Within a single boot cycle, session countdown MUST be driven by monotonic hardware timers (`SystemClock.elapsedRealtime()`).
- Session expiry is computed as a monotonic deadline (`sessionExpiryDeadlineMs = SystemClock.elapsedRealtime() + remainingSeconds * 1000L`).
- Monotonic timing is immune to wall-clock manipulation, daylight savings adjustments, or network time sync jumps.

### 2.2 Reboot Recovery & Persistence
- To survive power outages or intentional device reboots, remaining session credit (`sessionTimeRemaining`) and state revisions are periodically and atomically persisted to Room DB (`paid_session_state`) and secure local storage.
- Upon device reboot, the application loads the persisted remaining credit, verifies that elapsed time hasn't naturally expired, re-anchors the monotonic deadline against current `elapsedRealtime()`, and resumes the session smoothly.

---

## 3. Engineering Process & Change Control

1. **One Commit per Prompt**: Every discrete hardening step or bugfix must be committed cleanly with a descriptive message before proceeding to subsequent tasks.
2. **No Unrelated Refactoring**: Do not clean up, reformat, or rename surrounding code, variables, or functions that are not directly involved in the scoped change.
3. **No Unneeded Dependencies**: Zero new third-party libraries without a concrete, documented technical justification.
4. **No Weakened Security Checks**: Never disable or bypass HMAC verification, signature validation, nonce replay protection, or TLS/encryption requirements to accommodate legacy or out-of-date clients.
5. **No Destructive Storage Migrations**: All Room database schema changes must be non-destructive, strictly additive, backed by unit migration tests, and accompanied by exported schema JSON files in `app/schemas/`.
6. **Artifact Traceability**: Binary artifacts in `website/` or release distributions must always be accompanied by cryptographic SHA-256 hashes and build metadata. Rebuilds must not be designated as field-tested without verification.
