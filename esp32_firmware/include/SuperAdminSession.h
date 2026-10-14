#ifndef SUPER_ADMIN_SESSION_H
#define SUPER_ADMIN_SESSION_H

#include <Arduino.h>

// Who is signed in as super admin: the owner who scanned the admin QR code (AdminQrAuth.h). The page keeps a session cookie.
bool superAdminSessionOk();       // this request carries the valid session cookie (every use keeps the session alive)
void superAdminSessionEnd();      // sign out: forget the session and tell the browser to drop the cookie
void handleSuperAdminChallenge(); // GET  /api/superadmin/challenge
void handleSuperAdminQrLogin();   // POST /api/superadmin/qr_login

#endif // SUPER_ADMIN_SESSION_H
