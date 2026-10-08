#ifndef WEB_DASHBOARD_HTML_H
#define WEB_DASHBOARD_HTML_H

#include <Arduino.h>

void streamPortalHtml();
// A coloured message box for the dashboard. kind: "ok", "warn", "bad" or ""; icon: a name from the icon sprite.
// Makes text from a request safe to put inside HTML.
String escapeHtmlText(String s);
String noteHtml(const char* kind, const char* icon, const String& html);
String renderDeviceOptions(String selectedIp);
String renderLicenseSlotsHtml();

#endif // WEB_DASHBOARD_HTML_H
