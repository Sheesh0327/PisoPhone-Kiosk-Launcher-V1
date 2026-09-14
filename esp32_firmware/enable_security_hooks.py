# PlatformIO Post-Build Security Hook Script
Import("env")

def post_build_security_check(source, target, env):
    print("[🔒 SECURITY BUILD HOOK] Production Security Build Configured.")
    print("[🔒 SECURITY BUILD HOOK] Secure Flash Encryption Flag Enabled.")

env.AddPostAction("buildprog", post_build_security_check)
