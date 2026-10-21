#ifndef WEB_DASHBOARD_COMPONENTS_H
#define WEB_DASHBOARD_COMPONENTS_H

#include <Arduino.h>

#include "Product.h"

#if PISO_HAS_PHONES
String renderDeviceOptions(String selectedIp);
String renderPhoneSlotsHtml();
#endif

#endif // WEB_DASHBOARD_COMPONENTS_H
