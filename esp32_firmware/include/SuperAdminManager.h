#ifndef SUPER_ADMIN_MANAGER_H
#define SUPER_ADMIN_MANAGER_H

#include <Arduino.h>

// ============================================================================
// VENDOR SUPER ADMIN CONSTANTS & GLOBALS
// ============================================================================
// The super admin is the owner who signed in with the admin QR code (SuperAdminSession.h): there is no password.
#define DEFAULT_VENDOR_SPLIT_PERCENT 50
#define VAULT_UNMASK_TIMEOUT_SECONDS 300UL

extern int vendorRevenueSplitPercent;
extern bool isVaultUnmasked;
extern unsigned long unmaskExpiryTimestamp;

// ============================================================================
// CORE SUPER ADMIN FUNCTIONS
// ============================================================================
void loadSuperAdminConfig();
void processSuperAdminLoop();
bool authenticateSuperAdmin();
void handleSuperAdminAuth();
void handleSuperAdminLock();
void handleSuperAdminFactoryReset();
void handleSuperAdminUnmask();
void handleSuperAdminResetVault();
void handleSuperAdminCollections();
void handleSuperAdminSaveSplit();

// ============================================================================
// UI RENDERING HELPERS
// ============================================================================

#endif // SUPER_ADMIN_MANAGER_H
