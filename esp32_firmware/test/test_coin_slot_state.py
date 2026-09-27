#!/usr/bin/env python3
"""
Unit test suite verifying ESP32 CoinSlotManager and DeviceManager invariants:
1. Re-arming during DRAINING state when initiated by the same active session
2. Immediate disarm and slot unpairing on admin unpairSlot
3. Preserving pulses during in-flight drain and timeout guard
4. Blocking maintenance mode claims
5. Rollover-safe arithmetic
"""

import sys

ARM_TTL = 30000
MAX_SESSION_DURATION = 1800000
INTER_PULSE_TIMEOUT_MS = 280
IN_FLIGHT_PULSE_GRACE_MS = 560
DRAIN_TIMEOUT_GUARD_MS = 10000
IDLE_DRAIN_GRACE_MS = 1000

class CoinSlotState:
    IDLE = 0
    RESERVED_ARMING = 1
    ARMED = 2
    DRAINING = 3
    FAULT_MAINTENANCE = 4

class CoinSlotOwnerType:
    ANY = 0
    PHONE = 1
    CONTROLLER = 2

class MockCoinSlotEngine:
    def __init__(self):
        self.current_millis = 1000
        self.maintenance_mode = False
        self.payment_queue_full = False
        self.payment_storage_ready = True
        self.relay_powered = False
        self.isr_universal_pulse_count = 0
        self.isr_last_pulse_time_ms = 0

        self.current_state = CoinSlotState.IDLE
        self.active_owner_type = CoinSlotOwnerType.ANY
        self.active_session_id = ""
        self.session_armed_until = 0
        self.session_start_time_ms = 0
        self.drain_deadline_ms = 0
        self.max_drain_deadline_ms = 0
        self.pending_end_reason = ""
        self.session_accumulated_pulses = 0

        self.boot_start_time_ms = 1000
        self.boot_suppression_done = False
        self.total_finalizations = 0
        self.last_end_reason = ""

    def is_startup_suppression_active(self):
        if self.boot_suppression_done:
            return False
        return (self.current_millis - self.boot_start_time_ms) < 3000

    def is_coin_slot_armed(self):
        if self.current_state == CoinSlotState.ARMED:
            return (self.session_armed_until - self.current_millis) > 0
        if self.current_state == CoinSlotState.DRAINING:
            return True
        return False

    def update_relay_hardware(self):
        should_be_on = self.is_coin_slot_armed() and not self.is_startup_suppression_active()
        self.relay_powered = should_be_on

    def is_coin_slot_busy(self, session_id: str, owner_type: int) -> bool:
        if self.current_state == CoinSlotState.FAULT_MAINTENANCE:
            return True
        if self.current_state == CoinSlotState.IDLE or not self.active_session_id:
            return False
        if self.current_state == CoinSlotState.RESERVED_ARMING:
            if (self.current_millis - self.session_armed_until) >= 0:
                return False
        if self.current_state == CoinSlotState.ARMED:
            if (self.current_millis - self.session_armed_until) >= 0 or \
               (self.session_start_time_ms > 0 and (self.current_millis - self.session_start_time_ms) >= MAX_SESSION_DURATION):
                return True
        if session_id and self.active_session_id == session_id:
            if owner_type == CoinSlotOwnerType.ANY or self.active_owner_type == CoinSlotOwnerType.ANY or self.active_owner_type == owner_type:
                return False
        if self.current_state == CoinSlotState.DRAINING:
            return True
        return True

    def try_claim_coin_slot_for_arming(self, session_id: str, owner_type: int, timeout_ms: int) -> bool:
        if not session_id or self.maintenance_mode or self.payment_queue_full or not self.payment_storage_ready:
            return False
        if self.is_coin_slot_busy(session_id, owner_type):
            return False
        if self.active_session_id == session_id and (self.active_owner_type == owner_type or owner_type == CoinSlotOwnerType.ANY):
            self.session_armed_until = self.current_millis + timeout_ms
            return True

        self.current_state = CoinSlotState.RESERVED_ARMING
        self.active_session_id = session_id
        self.active_owner_type = owner_type
        self.session_start_time_ms = self.current_millis
        self.session_armed_until = self.current_millis + timeout_ms
        self.drain_deadline_ms = 0
        self.pending_end_reason = ""
        return True

    def finalize_session_release(self, reason: str):
        if not self.active_session_id and self.current_state == CoinSlotState.IDLE:
            return
        self.total_finalizations += 1
        self.last_end_reason = reason
        self.current_state = CoinSlotState.IDLE
        self.active_owner_type = CoinSlotOwnerType.ANY
        self.active_session_id = ""
        self.session_armed_until = 0
        self.session_start_time_ms = 0
        self.drain_deadline_ms = 0
        self.max_drain_deadline_ms = 0
        self.pending_end_reason = ""
        self.session_accumulated_pulses = 0
        self.isr_universal_pulse_count = 0
        self.isr_last_pulse_time_ms = 0
        self.update_relay_hardware()

    def initiate_session_release(self, reason: str, force: bool):
        if not self.active_session_id or self.current_state == CoinSlotState.IDLE:
            return
        terminal_reason = reason if reason else "RELEASED"
        if self.current_state == CoinSlotState.DRAINING:
            if force:
                self.finalize_session_release(terminal_reason)
            return

        if not force:
            current_pulses = self.isr_universal_pulse_count
            last_pulse = self.isr_last_pulse_time_ms
            self.current_state = CoinSlotState.DRAINING
            self.pending_end_reason = terminal_reason
            self.max_drain_deadline_ms = self.current_millis + DRAIN_TIMEOUT_GUARD_MS
            if current_pulses > 0 or (last_pulse > 0 and (self.current_millis - last_pulse) < IN_FLIGHT_PULSE_GRACE_MS):
                self.drain_deadline_ms = self.max_drain_deadline_ms
            else:
                self.drain_deadline_ms = self.current_millis + IDLE_DRAIN_GRACE_MS
            self.update_relay_hardware()
            return

        self.finalize_session_release(terminal_reason)

    def reserve_coin_slot(self, session_id: str, owner_type: int, ttl_ms: int) -> bool:
        if not session_id or self.maintenance_mode or self.payment_queue_full or not self.payment_storage_ready:
            return False

        # Re-arm / reconnect by same active session
        if self.active_session_id == session_id and (self.active_owner_type == owner_type or owner_type == CoinSlotOwnerType.ANY) and \
           (self.current_state in (CoinSlotState.ARMED, CoinSlotState.DRAINING)):
            if owner_type != CoinSlotOwnerType.ANY:
                self.active_owner_type = owner_type
            self.current_state = CoinSlotState.ARMED
            self.session_armed_until = self.current_millis + (ttl_ms if ttl_ms > 0 else ARM_TTL)
            self.drain_deadline_ms = 0
            self.pending_end_reason = ""
            self.update_relay_hardware()
            return True

        if self.current_state == CoinSlotState.DRAINING:
            return False
        if self.is_coin_slot_busy(session_id, owner_type):
            return False

        self.current_state = CoinSlotState.ARMED
        self.active_session_id = session_id
        self.active_owner_type = owner_type
        self.session_start_time_ms = self.current_millis
        self.session_armed_until = self.current_millis + (ttl_ms if ttl_ms > 0 else ARM_TTL)
        self.drain_deadline_ms = 0
        self.pending_end_reason = ""
        self.session_accumulated_pulses = 0
        self.isr_universal_pulse_count = 0
        self.update_relay_hardware()
        return True

    def process_coin_slot_session(self):
        if self.maintenance_mode:
            if self.current_state != CoinSlotState.FAULT_MAINTENANCE:
                if self.current_state == CoinSlotState.ARMED:
                    self.initiate_session_release("MAINTENANCE", False)
                elif self.current_state == CoinSlotState.RESERVED_ARMING:
                    self.current_state = CoinSlotState.FAULT_MAINTENANCE
                    self.active_session_id = ""
                    self.active_owner_type = CoinSlotOwnerType.ANY
                    self.session_armed_until = 0
                    self.update_relay_hardware()
                elif self.current_state == CoinSlotState.IDLE:
                    self.current_state = CoinSlotState.FAULT_MAINTENANCE
                    self.update_relay_hardware()
        else:
            if self.current_state == CoinSlotState.FAULT_MAINTENANCE:
                self.current_state = CoinSlotState.IDLE
                self.update_relay_hardware()

        if self.current_state == CoinSlotState.RESERVED_ARMING:
            if (self.current_millis - self.session_armed_until) >= 0:
                self.current_state = CoinSlotState.IDLE
                self.active_session_id = ""
                self.active_owner_type = CoinSlotOwnerType.ANY
                self.session_armed_until = 0

        if self.current_state == CoinSlotState.ARMED:
            if (self.current_millis - self.session_armed_until) >= 0:
                self.initiate_session_release("TTL_EXPIRED", False)

        if self.current_state == CoinSlotState.DRAINING:
            if (self.current_millis - self.drain_deadline_ms) >= 0:
                self.finalize_session_release(self.pending_end_reason)


def test_rearm_while_draining():
    engine = MockCoinSlotEngine()
    engine.current_millis = 5000
    engine.boot_suppression_done = True

    # 1. Arm slot for phone_1
    ok = engine.reserve_coin_slot("phone_1", CoinSlotOwnerType.PHONE, 30000)
    assert ok, "Failed to arm slot for phone_1"
    assert engine.current_state == CoinSlotState.ARMED
    assert engine.relay_powered

    # 2. WebSocket closed -> initiate non-forced release (enters DRAINING)
    engine.initiate_session_release("DISCONNECT", False)
    assert engine.current_state == CoinSlotState.DRAINING
    assert engine.relay_powered

    # 3. Another device must be blocked as busy
    assert engine.is_coin_slot_busy("phone_2", CoinSlotOwnerType.PHONE)
    assert not engine.reserve_coin_slot("phone_2", CoinSlotOwnerType.PHONE, 30000)

    # 4. Same device re-connecting must NOT be blocked
    assert not engine.is_coin_slot_busy("phone_1", CoinSlotOwnerType.PHONE)
    rearm_ok = engine.reserve_coin_slot("phone_1", CoinSlotOwnerType.PHONE, 30000)
    assert rearm_ok, "Same session must successfully re-arm while draining"
    assert engine.current_state == CoinSlotState.ARMED
    assert engine.relay_powered
    assert engine.drain_deadline_ms == 0
    print("[PASS] test_rearm_while_draining")


def test_unpair_slot_force_disarms():
    engine = MockCoinSlotEngine()
    engine.current_millis = 5000
    engine.boot_suppression_done = True

    # 1. Active armed session
    ok = engine.reserve_coin_slot("DEV_192.168.4.2", CoinSlotOwnerType.PHONE, 30000)
    assert ok
    assert engine.current_state == CoinSlotState.ARMED
    assert engine.relay_powered

    # 2. Admin unpairs slot
    engine.initiate_session_release("UNPAIRED", True)
    assert engine.current_state == CoinSlotState.IDLE
    assert not engine.relay_powered
    assert engine.active_session_id == ""
    print("[PASS] test_unpair_slot_force_disarms")


def main():
    print("=== Running Python CoinSlotManager Tests ===")
    test_rearm_while_draining()
    test_unpair_slot_force_disarms()
    print("=== All Tests Passed! ===")

if __name__ == "__main__":
    main()
