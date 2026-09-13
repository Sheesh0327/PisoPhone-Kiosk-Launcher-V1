#ifndef SUPER_ADMIN_MANAGER_H
#define SUPER_ADMIN_MANAGER_H

#include <Arduino.h>

// ============================================================================
// VENDOR SUPER ADMIN CONSTANTS & GLOBALS
// ============================================================================
#define DEFAULT_SUPER_ADMIN_PW "superadmin123"
#define DEFAULT_VENDOR_SPLIT_PERCENT 50
#define VAULT_UNMASK_TIMEOUT_SECONDS 300UL

extern String superAdminPassword;
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
void handleSuperAdminUnmask();
void handleSuperAdminResetVault();
void handleSuperAdminSaveSplit();
void handleSuperAdminChangePassword();

// ============================================================================
// UI RENDERING HELPERS
// ============================================================================
String renderSuperAdminTabHtml();
String renderSuperAdminScripts();

#endif // SUPER_ADMIN_MANAGER_H
