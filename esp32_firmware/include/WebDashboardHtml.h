#ifndef WEB_DASHBOARD_HTML_H
#define WEB_DASHBOARD_HTML_H

#include <Arduino.h>

void streamPortalHtml();
String renderDeviceOptions(String selectedIp);
String renderDeviceIpInputs();
String renderLicenseSlotsHtml();
String renderSlotOptions();

#endif // WEB_DASHBOARD_HTML_H
