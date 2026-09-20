#include <iostream>
#include <string>
#include <cassert>
#include <cstring>
#include <vector>
#include <cstdint>
#include <functional>

// ============================================================================
// SIMULATED TEST HARNESS FOR MAINTENANCE GATE & REBOOT INVARIANTS
// ============================================================================

enum class MockCoinSlotState {
    IDLE,
    RESERVED_ARMING,
    ARMED,
    DRAINING,
    FAULT_MAINTENANCE
};

struct MockMaintenanceHarness {
    bool maintenanceMode = false;
    bool unpersistedPaymentsInRam = false;
    MockCoinSlotState coinSlotState = MockCoinSlotState::IDLE;
    bool isArmed = false;
    int isrPulses = 0;
    int sessionAccumulatedPulses = 0;
    
    bool revenueDirty = false;
    uint32_t totalCoins = 100;
    uint32_t savedCoins = 100;
    
    bool restartExecuted = false;
    std::string restartReason = "";

    void setMaintenanceMode(bool enable) {
        maintenanceMode = enable;
    }

    bool isMaintenanceMode() const {
        return maintenanceMode;
    }

    bool canPerformRebootOrOta() const {
        if (unpersistedPaymentsInRam) return false;
        if (coinSlotState != MockCoinSlotState::IDLE) return false;
        if (isArmed) return false;
        if (isrPulses > 0) return false;
        if (sessionAccumulatedPulses > 0) return false;
        return true;
    }

    bool requestSystemRestart(const char* reason, unsigned long timeoutMs = 5000) {
        setMaintenanceMode(true);

        // Simulate drain check
        if (!canPerformRebootOrOta()) {
            // If condition remains blocking, abort restart
            setMaintenanceMode(false);
            return false;
        }

        // Flush revenue
        if (revenueDirty || totalCoins != savedCoins) {
            savedCoins = totalCoins;
            revenueDirty = false;
        }

        restartExecuted = true;
        restartReason = reason ? reason : "";
        return true;
    }
};

void test_clean_idle_restart() {
    MockMaintenanceHarness harness;
    assert(harness.canPerformRebootOrOta() == true);
    bool ok = harness.requestSystemRestart("Admin Portal Reboot");
    assert(ok == true);
    assert(harness.restartExecuted == true);
    assert(harness.restartReason == "Admin Portal Reboot");
    std::cout << "[PASS] test_clean_idle_restart\n";
}

void test_blocked_by_unpersisted_ram_money() {
    MockMaintenanceHarness harness;
    harness.unpersistedPaymentsInRam = true;
    
    assert(harness.canPerformRebootOrOta() == false);
    bool ok = harness.requestSystemRestart("Scheduled Maintenance");
    assert(ok == false);
    assert(harness.restartExecuted == false);
    assert(harness.isMaintenanceMode() == false); // Admission reopened after abort
    std::cout << "[PASS] test_blocked_by_unpersisted_ram_money\n";
}

void test_blocked_by_armed_slot() {
    MockMaintenanceHarness harness;
    harness.isArmed = true;
    harness.coinSlotState = MockCoinSlotState::ARMED;

    assert(harness.canPerformRebootOrOta() == false);
    bool ok = harness.requestSystemRestart("OTA Firmware Update");
    assert(ok == false);
    assert(harness.restartExecuted == false);
    std::cout << "[PASS] test_blocked_by_armed_slot\n";
}

void test_blocked_by_in_flight_isr_pulses() {
    MockMaintenanceHarness harness;
    harness.isrPulses = 2;

    assert(harness.canPerformRebootOrOta() == false);
    bool ok = harness.requestSystemRestart("Hardware Pin Reset");
    assert(ok == false);
    assert(harness.restartExecuted == false);
    std::cout << "[PASS] test_blocked_by_in_flight_isr_pulses\n";
}

void test_revenue_flushed_before_restart() {
    MockMaintenanceHarness harness;
    harness.totalCoins = 250;
    harness.savedCoins = 200;
    harness.revenueDirty = true;

    assert(harness.canPerformRebootOrOta() == true);
    bool ok = harness.requestSystemRestart("OTA Firmware Update");
    assert(ok == true);
    assert(harness.savedCoins == 250);
    assert(harness.revenueDirty == false);
    assert(harness.restartExecuted == true);
    std::cout << "[PASS] test_revenue_flushed_before_restart\n";
}

int main() {
    std::cout << "Running Maintenance Gate Unit Tests...\n";
    test_clean_idle_restart();
    test_blocked_by_unpersisted_ram_money();
    test_blocked_by_armed_slot();
    test_blocked_by_in_flight_isr_pulses();
    test_revenue_flushed_before_restart();
    std::cout << "All Maintenance Gate tests passed successfully!\n";
    return 0;
}
