# PlatformIO Post-Build Security Hook for ESP32 Production Flash Encryption
Import("env")

def print_security_notice(source, target, env):
    print("=" * 80)
    print(" [SECURITY] PRODUCTION FLASH ENCRYPTION ENVIRONMENT ENABLED!")
    print("=" * 80)
    print(" Steps to complete Flash Encryption hardware lockdown on target ESP32:")
    print(" 1. Ensure Python esptool/espefuse is installed: pip install esptool")
    print(" 2. To burn Flash Encryption key into eFuses (Development/Release mode):")
    print("    espefuse.py -p COM_PORT burn_key FLASH_CRYPT_CNT 1")
    print("    espefuse.py -p COM_PORT burn_efuse SPI_BOOT_CRYPT_CNT 1")
    print(" 3. Upload firmware compiled under this environment.")
    print(" 4. Upon first boot, the ESP32 bootloader encrypts all flash partitions in-place.")
    print("=" * 80)

env.AddPostAction("buildprog", print_security_notice)
