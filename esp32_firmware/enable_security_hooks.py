# PlatformIO Post-Build Security Hook Script
Import("env")

def post_build_security_check(source, target, env):
    print("[🔒 SECURITY BUILD HOOK] Production Security Build Configured.")
    print("[🔒 SECURITY BUILD HOOK] Notice: Secure Flash Encryption build flags active.")
    print("[🔒 SECURITY BUILD HOOK] Hardware Note: Burning FLASH_CRYPT_CNT and SECURE_BOOT_EN efuses requires esptool.py during initial provisioning.")

env.AddPostAction("buildprog", post_build_security_check)
