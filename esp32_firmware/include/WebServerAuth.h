#ifndef WEB_SERVER_AUTH_H
#define WEB_SERVER_AUTH_H

#include <Arduino.h>

void authWorkerTask(void *pvParameters);
bool checkAuth();
bool checkAdminAuth();
void redirectHome();
void handleLogout();

#endif // WEB_SERVER_AUTH_H
