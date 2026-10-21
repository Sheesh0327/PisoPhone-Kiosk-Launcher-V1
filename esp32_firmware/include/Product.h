#ifndef PRODUCT_H
#define PRODUCT_H

// Which product this image is. The full TechNet PisoHub box (no flag) does both jobs; the PisoPhone-only image (PISO_PRODUCT_PHONE)
// has no router gateway code at all and the PisoWiFi-only image (PISO_PRODUCT_WIFI) has no phone code at all: those files are not
// compiled in (envs/products.ini), so the other product leaves no trace in the binary.

#if defined(PISO_PRODUCT_PHONE) && defined(PISO_PRODUCT_WIFI)
#error "an image is for one product: define PISO_PRODUCT_PHONE or PISO_PRODUCT_WIFI, not both"
#endif

#if defined(PISO_PRODUCT_PHONE)
#define PISO_HAS_GATEWAY 0
#define PISO_HAS_PHONES 1
#define PISO_PRODUCT_NAME "TechNet PisoPhone"
#define PISO_FEED_PATH "/update/phone"
#define PISO_SITE_NAME "PisoPhone"
#elif defined(PISO_PRODUCT_WIFI)
#define PISO_HAS_GATEWAY 1
#define PISO_HAS_PHONES 0
#define PISO_PRODUCT_NAME "TechNet PisoWiFi"
#define PISO_FEED_PATH "/update/wifi"
#define PISO_SITE_NAME "TechNet PisoWiFi"
#else
#define PISO_HAS_GATEWAY 1
#define PISO_HAS_PHONES 1
#define PISO_PRODUCT_NAME "TechNet PisoHub"
#define PISO_FEED_PATH "/update"
#define PISO_SITE_NAME "PisoPhone"
#endif

#endif
