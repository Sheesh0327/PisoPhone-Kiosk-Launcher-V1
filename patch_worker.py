import os

path = "website/cloudflare_worker.js"
with open(path, "r") as f:
    content = f.read()

# Replace in /api/user/link-device
target1 = "const cleanId = String(deviceId).trim().replace(/^HW-/i, '');"
repl1 = """let cleanId = String(deviceId).trim();
        if (!cleanId.toUpperCase().startsWith('HW-')) {
            cleanId = 'HW-' + cleanId;
        }"""
content = content.replace(target1, repl1)

# In link-device creation block:
target2 = """            deviceId: cleanId,
            hardwareHash: `HW-${cleanId.toUpperCase()}`,"""
repl2 = """            deviceId: cleanId,
            hardwareHash: cleanId.toUpperCase(),"""
content = content.replace(target2, repl2)

# Replace in /api/user/activate-device
target3 = "const cleanId = deviceId.replace(/^HW-/i, '');"
repl3 = """let cleanId = String(deviceId).trim();
          if (!cleanId.toUpperCase().startsWith('HW-')) {
              cleanId = 'HW-' + cleanId;
          }"""
content = content.replace(target3, repl3)

target4 = """            deviceId: cleanId,
            hardwareHash: `HW-${cleanId.toUpperCase()}`,"""
repl4 = """            deviceId: cleanId,
            hardwareHash: cleanId.toUpperCase(),"""
content = content.replace(target4, repl4)

with open(path, "w") as f:
    f.write(content)
print("Patched worker!")
