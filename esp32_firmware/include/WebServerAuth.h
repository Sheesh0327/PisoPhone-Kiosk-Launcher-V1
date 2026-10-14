#ifndef WEB_SERVER_AUTH_H
#define WEB_SERVER_AUTH_H

#include <Arduino.h>

void authWorkerTask(void* pvParameters);
// The AuthWorker task must not touch the account table or the phone slots (loop() owns them, with no lock). It hands each
// confirmed phone acknowledgement to loop() through this queue; loop() applies it by calling processWorkerAcks().
void initWorkerAckQueue();
void processWorkerAcks();
bool checkAuth();
bool checkAdminAuth();
// Like checkAdminAuth() for the dashboard page: a browser that has no credentials yet gets the sign-in page (admin password or
// the owner's admin QR code) instead of the browser's own password box.
bool checkAdminAuthOrLoginPage();
void handleSignIn(); // /signin: the admin password prompt, then back to the page that was asked for (and a session cookie, AdminSession.h)
void adminSessionsEndAll(); // every browser has to give the password again (called when it is changed)
// The login throttle, shared with the admin QR login (five failures lock a client out for a minute).
unsigned long adminLoginLockedMs();
void adminLoginRecordFailure();
void adminLoginRecordSuccess();
bool defaultCredentialsActive();
void redirectHome();
void handleLogout();

#endif // WEB_SERVER_AUTH_H
