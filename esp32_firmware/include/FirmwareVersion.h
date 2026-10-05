#ifndef FIRMWARE_VERSION_H
#define FIRMWARE_VERSION_H

// Bump this when releasing a new firmware.bin. scripts/update_firmware_json.py publishes the same
// string in firmware.json, and the dashboard compares the two to decide whether an update exists.
#define PISO_FW_VERSION "3.2.0"

#endif // FIRMWARE_VERSION_H
