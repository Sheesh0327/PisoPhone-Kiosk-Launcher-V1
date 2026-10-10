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
bool defaultCredentialsActive();
void redirectHome();
void handleLogout();

#endif // WEB_SERVER_AUTH_H
