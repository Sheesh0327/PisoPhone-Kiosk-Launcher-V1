#ifndef SUPER_ADMIN_MANAGER_H
#define SUPER_ADMIN_MANAGER_H

#include <Arduino.h>

#define DEFAULT_SUPER_ADMIN_PW "10203040"
#define DEFAULT_VENDOR_SPLIT_PERCENT 30
#define VAULT_UNMASK_TIMEOUT_SECONDS 300 // 5-Minute Auto-Reset Protection

extern String superAdminPassword;
extern int vendorRevenueSplitPercent;
extern bool isVaultUnmasked;
extern unsigned long unmaskExpiryTimestamp;

void loadSuperAdminConfig();
bool authenticateSuperAdmin();
void processSuperAdminLoop();

void handleSuperAdminAuth();
void handleSuperAdminUnmask();
void handleSuperAdminResetVault();
void handleSuperAdminSaveSplit();
void handleSuperAdminChangePassword();

String renderSuperAdminTabHtml();
String renderSuperAdminScripts();

#endif // SUPER_ADMIN_MANAGER_H
