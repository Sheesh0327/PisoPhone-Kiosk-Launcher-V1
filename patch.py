import os

path = "website/activate.html"
with open(path, "r") as f:
    content = f.read()

# Replace 1
target1 = """            <!-- Select Device -->
            <div class="glass-card rounded-2xl p-6 shadow-xl flex flex-col gap-4">
                <label class="block text-xs font-bold text-slate-400 uppercase tracking-wider">Step 1: Select a Linked Device</label>
                <div id="deviceListLoading" class="text-sm text-slate-500 italic">Loading your devices...</div>
                <div id="deviceList" class="flex flex-col gap-3"></div>
            </div>"""

repl1 = """            <!-- Select Device -->
            <div class="glass-card rounded-2xl p-6 shadow-xl flex flex-col gap-4">
                <div class="flex justify-between items-center">
                    <label class="block text-xs font-bold text-slate-400 uppercase tracking-wider">Step 1: Select or Link a Device</label>
                </div>
                <div class="flex gap-2">
                    <input type="text" id="linkDeviceIdInput" placeholder="Enter Hardware ID (e.g. HW-XXXX)" class="w-full bg-slate-900 border border-slate-700 rounded-lg p-3 text-sm text-white focus:outline-none focus:border-emerald-500">
                    <button id="linkDeviceBtn" class="bg-slate-700 hover:bg-slate-600 text-white px-4 py-2 rounded-lg text-sm font-bold transition whitespace-nowrap">Link Device</button>
                </div>
                <div id="deviceListLoading" class="text-sm text-slate-500 italic mt-2">Loading your devices...</div>
                <div id="deviceList" class="flex flex-col gap-3 mt-2"></div>
            </div>"""

content = content.replace(target1, repl1)

# Replace 2
target2 = """        const useCreditBtn = document.getElementById('useCreditBtn');
        const qrCodeSection = document.getElementById('qrCodeSection');"""

repl2 = """        const useCreditBtn = document.getElementById('useCreditBtn');
        const linkDeviceBtn = document.getElementById('linkDeviceBtn');
        const linkDeviceIdInput = document.getElementById('linkDeviceIdInput');
        const qrCodeSection = document.getElementById('qrCodeSection');"""

content = content.replace(target2, repl2)

# Replace 3
target3 = """                        deviceList.innerHTML = `<p class="text-sm text-slate-400 text-center py-4">No linked devices found. Please install the app on a device first.</p>`;
                    }
                }
            } catch (err) {
                deviceListLoading.textContent = 'Failed to load devices.';
            }
        }

        function selectDevice(dev, btnElem) {"""

repl3 = """                        deviceList.innerHTML = `<p class="text-sm text-slate-400 text-center py-4">No linked devices found. Enter a Hardware ID above to link your first device.</p>`;
                    }
                }
            } catch (err) {
                deviceListLoading.textContent = 'Failed to load devices.';
            }
        }

        linkDeviceBtn.onclick = async () => {
            const deviceId = linkDeviceIdInput.value.trim();
            if (!deviceId) return;
            
            linkDeviceBtn.disabled = true;
            const oldText = linkDeviceBtn.innerHTML;
            linkDeviceBtn.innerHTML = 'Linking...';
            
            try {
                const res = await fetch(`${API_BASE}/api/user/link-device`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ ownerToken, deviceId })
                });
                const data = await res.json();
                
                if (res.ok) {
                    linkDeviceIdInput.value = '';
                    await fetchDevices();
                } else {
                    alert(`Failed to link device: ${data.error}`);
                }
            } catch (err) {
                alert(`Network error linking device: ${err.message}`);
            } finally {
                linkDeviceBtn.disabled = false;
                linkDeviceBtn.innerHTML = oldText;
            }
        };

        function selectDevice(dev, btnElem) {"""

content = content.replace(target3, repl3)

with open(path, "w") as f:
    f.write(content)
print("Patched!")
