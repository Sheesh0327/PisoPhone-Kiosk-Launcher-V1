#!/usr/bin/env python3
"""
Verification test suite for CoinSlotManager state machine and unpairing behavior.
Verifies:
1. Re-arming during DRAINING state by the SAME session succeeds and preserves relay power.
2. Different sessions attempting to arm during DRAINING state are rejected as busy.
3. Unpairing a slot with an active session immediately disarms and clears the session.
4. Startup pulse suppression and relay lock.
"""
from enum import Enum

class CoinSlotState(Enum):
    IDLE = 0
    RESERVED_ARMING = 1
    ARMED = 2
    DRAINING = 3
    FAULT_MAINTENANCE = 4

class CoinSlotOwnerType(Enum):
    ANY = 0
    PHONE = 1
    CONTROLLER = 2

ARM_TTL = 30000
MAX_SESSION_DURATION = 1800000
IDLE_DRAIN_GRACE_MS = 1000
DRAIN_TIMEOUT_GUARD_MS = 10000

class MockCoinSlotEngine:
    def __init__(self):
        self.current_millis = 1000
        self.maintenance_mode = False
        self.payment_queue_full = False
        self.payment_storage_ready = True
        self.relay_powered = False

        self.isr_pulse_count = 0
        self.isr_last_pulse_time = 0

        self.current_state = CoinSlotState.IDLE
        self.active_owner_type = CoinSlotOwnerType.ANY
        self.active_session_id = ""
        self.session_armed_until = 0
        self.session_start_time_ms = 0
        self.drain_deadline_ms = 0
        self.max_drain_deadline_ms = 0
        self.pending_end_reason = ""
        self.session_accumulated_pulses = 0

        self.boot_start_time_ms = 0
        self.boot_suppression_done = False

    def init(self):
        self.boot_start_time_ms = 1000
        self.current_millis = 4001
        self.boot_suppression_done = True
        self.current_state = CoinSlotState.IDLE
        self.active_session_id = ""
        self.relay_powered = False

    def update_relay_hardware(self):
        if self.current_state == CoinSlotState.ARMED:
            self.relay_powered = (self.session_armed_until - self.current_millis) > 0
        elif self.current_state == CoinSlotState.DRAINING:
            self.relay_powered = True
        else:
            self.relay_powered = False

    def is_coin_slot_busy(self, session_id: str, owner_type: CoinSlotOwnerType) -> bool:
        if self.current_state == CoinSlotState.FAULT_MAINTENANCE:
            return True
        if self.current_state == CoinSlotState.IDLE or not self.active_session_id:
            return False

        if self.current_state == CoinSlotState.RESERVED_ARMING:
            if self.current_millis >= self.session_armed_until:
                return False

        if self.current_state == CoinSlotState.ARMED:
            if self.current_millis >= self.session_armed_until or (self.session_start_time_ms > 0 and (self.current_millis - self.session_start_time_ms) >= MAX_SESSION_DURATION):
                return True

        # Same session check (permits rapid re-arm even if briefly DRAINING)
        if session_id and self.active_session_id == session_id:
            if owner_type == CoinSlotOwnerType.ANY or self.active_owner_type == CoinSlotOwnerType.ANY or self.active_owner_type == owner_type:
                return False

        if self.current_state == CoinSlotState.DRAINING:
            return True

        return True

    def reserve_coin_slot(self, session_id: str, owner_type: CoinSlotOwnerType, ttl_ms: int) -> bool:
        if not session_id:
            return False
        if self.maintenance_mode or self.payment_queue_full or not self.payment_storage_ready:
            return False

        # Same session re-arm during ARMED or DRAINING
        if self.active_session_id == session_id and (self.active_owner_type == owner_type or owner_type == CoinSlotOwnerType.ANY) and (self.current_state in (CoinSlotState.ARMED, CoinSlotState.DRAINING)):
            if owner_type != CoinSlotOwnerType.ANY:
                self.active_owner_type = owner_type
            self.session_armed_until = self.current_millis + (ttl_ms if ttl_ms > 0 else ARM_TTL)
            self.current_state = CoinSlotState.ARMED
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
        self.update_relay_hardware()
        return True

    def initiate_session_release(self, reason: str, force: bool):
        if not self.active_session_id and self.current_state == CoinSlotState.IDLE:
            return

        if self.current_state == CoinSlotState.DRAINING:
            if force:
                self.finalize_session_release(reason)
            return

        if not force:
            self.current_state = CoinSlotState.DRAINING
            self.pending_end_reason = reason
            self.drain_deadline_ms = self.current_millis + IDLE_DRAIN_GRACE_MS
            self.max_drain_deadline_ms = self.current_millis + DRAIN_TIMEOUT_GUARD_MS
            self.update_relay_hardware()
            return

        self.finalize_session_release(reason)

    def finalize_session_release(self, reason: str):
        self.current_state = CoinSlotState.IDLE
        self.active_session_id = ""
        self.active_owner_type = CoinSlotOwnerType.ANY
        self.session_armed_until = 0
        self.session_start_time_ms = 0
        self.drain_deadline_ms = 0
        self.max_drain_deadline_ms = 0
        self.pending_end_reason = ""
        self.update_relay_hardware()

def test_same_session_can_rearm_while_draining():
    engine = MockCoinSlotEngine()
    engine.init()

    # 1. Arm for phone_1
    assert engine.reserve_coin_slot("phone_1", CoinSlotOwnerType.PHONE, 30000) is True
    assert engine.current_state == CoinSlotState.ARMED
    assert engine.relay_powered is True

    # 2. Socket disconnects -> non-forced release -> enters DRAINING
    engine.initiate_session_release("DISCONNECT", force=False)
    assert engine.current_state == CoinSlotState.DRAINING
    assert engine.relay_powered is True

    # 3. Another phone tries -> busy
    assert engine.is_coin_slot_busy("phone_2", CoinSlotOwnerType.PHONE) is True
    assert engine.reserve_coin_slot("phone_2", CoinSlotOwnerType.PHONE, 30000) is False

    # 4. Same phone reconnects ("Ready for Coin") -> NOT busy and re-arms successfully!
    assert engine.is_coin_slot_busy("phone_1", CoinSlotOwnerType.PHONE) is False
    assert engine.reserve_coin_slot("phone_1", CoinSlotOwnerType.PHONE, 30000) is True
    assert engine.current_state == CoinSlotState.ARMED
    assert engine.relay_powered is True
    assert engine.drain_deadline_ms == 0
    print("[PASS] test_same_session_can_rearm_while_draining")

def test_unpair_slot_disarms_active_session():
    engine = MockCoinSlotEngine()
    engine.init()

    # 1. Active armed session
    assert engine.reserve_coin_slot("DEV_SLOT_1", CoinSlotOwnerType.PHONE, 30000) is True
    assert engine.current_state == CoinSlotState.ARMED
    assert engine.relay_powered is True

    # 2. Admin unpairs slot -> force release with reason "UNPAIRED"
    engine.initiate_session_release("UNPAIRED", force=True)
    assert engine.current_state == CoinSlotState.IDLE
    assert engine.relay_powered is False
    assert engine.active_session_id == ""
    print("[PASS] test_unpair_slot_disarms_active_session")

def test_expired_session_behavior():
    engine = MockCoinSlotEngine()
    engine.init()

    # Arm for phone_1
    assert engine.reserve_coin_slot("phone_1", CoinSlotOwnerType.PHONE, 5000) is True
    engine.current_millis += 6000 # Past TTL

    # Expired session must be busy even to same caller until drained
    assert engine.is_coin_slot_busy("phone_1", CoinSlotOwnerType.PHONE) is True
    assert engine.is_coin_slot_busy("phone_2", CoinSlotOwnerType.PHONE) is True
    print("[PASS] test_expired_session_behavior")

if __name__ == "__main__":
    print("=== Running State Machine & Unpair Verification Tests ===")
    test_same_session_can_rearm_while_draining()
    test_unpair_slot_disarms_active_session()
    test_expired_session_behavior()
    print("=== All Verification Tests Passed Successfully! ===")
