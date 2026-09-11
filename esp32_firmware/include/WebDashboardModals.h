#ifndef WEB_DASHBOARD_MODALS_H
#define WEB_DASHBOARD_MODALS_H

#include <Arduino.h>

const char PORTAL_MODALS_HTML[] PROGMEM = R"HTML(
<!-- Slot Management Modal -->
<div id="slot_activation_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.65); backdrop-filter: blur(4px); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
    <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: 20px; padding: 24px; max-width: 420px; width: 100%; box-shadow: 0 20px 40px rgba(0,0,0,0.3);">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; border-bottom: 1px solid var(--border); padding-bottom: 14px;">
            <div style="display: flex; align-items: center; gap: 10px;">
                <div style="width: 38px; height: 38px; border-radius: 10px; background: rgba(16, 185, 129, 0.15); color: var(--primary); display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 800;">🗄️</div>
                <div>
                    <h3 id="modal_slot_title" style="margin: 0; font-size: 16px; font-weight: 800; color: var(--text-main);">Slot #1 Seat</h3>
                    <div id="modal_slot_sub" style="font-size: 11px; color: var(--text-muted); margin-top: 2px;">Permanent Seat Management</div>
                </div>
            </div>
            <button type="button" onclick="closeSlotActivationModal()" style="background: none; border: none; font-size: 22px; cursor: pointer; color: var(--text-muted); padding: 4px; line-height: 1;">&times;</button>
        </div>

        <!-- License & Status Info Box -->
        <div style="background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 16px; margin-bottom: 18px; display: flex; justify-content: space-between; align-items: center;">
            <div>
                <div style="font-size: 11px; color: var(--text-muted); font-weight: 600;">License Status</div>
                <div id="modal_slot_expiry_text" style="font-size: 14px; font-weight: 800; color: var(--text-main); margin-top: 2px;">Permanent (₱500 Seat)</div>
            </div>
            <div id="modal_slot_status_badge">
                <span style="font-size: 10px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); padding: 3px 8px; border-radius: 6px;">🟢 ACTIVE</span>
            </div>
        </div>

        <!-- Action Buttons -->
        <div style="display: flex; flex-direction: column; gap: 10px; margin-bottom: 12px;">
            <button type="button" onclick="submitModalFlash()" style="background: var(--input-bg); border: 1px solid rgba(59, 130, 246, 0.4); border-radius: 12px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; transition: all 0.15s ease; text-align: left;" onmouseover="this.style.background='rgba(59, 130, 246, 0.08)';" onmouseout="this.style.background='var(--input-bg)';">
                <div style="display: flex; align-items: center; gap: 12px;">
                    <span style="font-size: 20px;">📥</span>
                    <div>
                        <div style="font-size: 13px; font-weight: 800; color: #3b82f6;">Flash / Provision Terminal</div>
                        <div style="font-size: 11px; color: var(--text-muted); margin-top: 1px;">Launch WebUSB installer for this slot</div>
                    </div>
                </div>
                <span style="font-size: 12px; font-weight: 800; background: rgba(59, 130, 246, 0.15); color: #3b82f6; padding: 4px 10px; border-radius: 8px;">Install</span>
            </button>
        </div>

        <div id="modal_unpair_container" style="display: none; border-top: 1px solid var(--border); margin-top: 12px; padding-top: 12px;">
            <button type="button" onclick="submitModalUnpair()" class="btn btn-outline" style="width: 100%; border-color: rgba(239, 68, 68, 0.4); color: var(--danger); font-size: 12px; font-weight: 700; padding: 8px 12px;">
                🔓 Unpair Terminal Seat
            </button>
        </div>
    </div>
</div>

<!-- Token Upgrade Modal -->
<div id="token_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
    <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 500px; width: 100%; box-shadow: var(--card-shadow);">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
            <h3 style="margin: 0; font-size: 18px; font-weight: 700;">🔑 Upgrade Hardware Capacity</h3>
            <button type="button" onclick="closeTokenModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
        </div>
        <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 12px;">Copy your <strong>Box Request Code</strong> below and send it to your vendor/provider to receive an 8-character license key.</p>
        <div style="background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 12px; margin-bottom: 14px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                <span style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase;">Box Request Code</span>
                <button type="button" class="btn btn-outline btn-sm" onclick="copyToClipboard('{BOX_CODE}', this)" style="padding: 2px 8px; font-size: 11px; font-weight: 700;">📋 Copy Code</button>
            </div>
            <code style="font-family: monospace; font-size: 13px; font-weight: 800; color: #34d399; display: block; word-break: break-all;">{BOX_CODE}</code>
        </div>
        <div style="background: rgba(59, 130, 246, 0.08); border: 1px solid rgba(59, 130, 246, 0.2); border-radius: var(--radius-sm); padding: 8px 12px; margin-bottom: 16px; font-size: 11px; color: var(--text-muted); display: flex; justify-content: space-between; align-items: center;">
            <span>Hardware Capacity</span>
            <span>Current: <strong style="color: var(--primary);">{MAX_SLOTS} / {MAX_SUPPORTED_SLOTS} Seats</strong></span>
        </div>
        <div class="form-group">
            <label style="font-size: 12px; font-weight: 700;">License Key / Slot Token</label>
            <textarea id="token_input" rows="2" placeholder="Paste 8-character license key (e.g. A1B2C3D4) or PISOSLOT token..." style="width: 100%; font-family: monospace; font-size: 13px; padding: 10px; border-radius: var(--radius-sm); border: 1px solid var(--border); background: var(--bg); color: var(--text-main);"></textarea>
        </div>
        <div id="token_error" style="display: none; color: var(--danger); font-size: 12px; margin-bottom: 12px; font-weight: 600;"></div>
        <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px;">
            <button type="button" class="btn btn-outline" onclick="closeTokenModal()">Cancel</button>
            <button type="button" class="btn btn-primary" onclick="submitSlotToken()">Verify & Upgrade</button>
        </div>
    </div>
</div>

<!-- QR Code Handshake Pairing Modal -->
<div id="qr_pair_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.7); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
    <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 440px; width: 100%; box-shadow: var(--card-shadow); text-align: center;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
            <h3 style="margin: 0; font-size: 18px; font-weight: 700;">📱 Pair Phone to Slot #<span id="qr_slot_title">1</span></h3>
            <button type="button" onclick="closePairingQrModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
        </div>
        <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px;">
            Open the PisoPhone Kiosk App on your phone and scan this QR code to initialize pairing and exchange shared cryptographic keys.
        </p>
        
        <div style="background: white; padding: 16px; border-radius: 12px; display: inline-block; box-shadow: 0 4px 12px rgba(0,0,0,0.1); margin-bottom: 14px;">
            <canvas id="qr_canvas" width="260" height="260" style="display: block; margin: 0 auto;"></canvas>
        </div>
        
        <div id="qr_fallback_text" style="display: none; font-family: monospace; font-size: 11px; word-break: break-all; color: var(--primary); margin-bottom: 12px;"></div>

        <div style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 10px; font-size: 12px; text-align: left; margin-bottom: 16px;">
            <div style="display: flex; justify-content: space-between;">
                <span style="color: var(--text-muted);">Cabinet IP:</span>
                <span id="qr_modal_ip" style="font-family: monospace; font-weight: 600;">{IP_ADDRESS}</span>
            </div>
        </div>

        <button type="button" class="btn btn-outline" style="width: 100%;" onclick="closePairingQrModal()">Done</button>
    </div>
</div>

<!-- WebUSB 1-Click Provisioning Modal -->
<div id="provision_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.65); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
    <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 480px; width: 100%; box-shadow: var(--card-shadow);">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
            <h3 style="margin: 0; font-size: 18px; font-weight: 700;">📱 Start Sideload & Pair (Slot #<span id="prov_slot_num">1</span>)</h3>
            <button type="button" onclick="closeProvisionModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
        </div>
        <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px; line-height: 1.5;">
            To bypass browser USB security blocks and enjoy zero-flag setup, PisoPhone uses an HTTPS-secured cloud flasher to flash, authorize, and link your terminal.
        </p>
        
        <div style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; margin-bottom: 18px; font-size: 12px; display: flex; flex-direction: column; gap: 8px;">
            <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                <span>✔</span> Secure HTTPS WebUSB Tunnel
            </div>
            <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                <span>✔</span> Over-The-Air APK Cache Delivery
            </div>
            <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                <span>✔</span> 1-Click Redirect Slot Association
            </div>
        </div>

        <div style="display: flex; justify-content: flex-end; gap: 8px;">
            <button type="button" class="btn btn-outline" onclick="closeProvisionModal()">Close</button>
            <button type="button" class="btn btn-primary" onclick="launchHttpsFlasher()">⚡ Proceed to Web Flasher</button>
        </div>
    </div>
</div>

<!-- WebUSB Deprovisioning Modal -->
<div id="deprovision_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.65); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
    <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 480px; width: 100%; box-shadow: var(--card-shadow);">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
            <h3 style="margin: 0; font-size: 18px; font-weight: 700; color: var(--danger);">🗑️ Deprovision Slot #<span id="deprov_slot_num">1</span></h3>
            <button type="button" onclick="closeDeprovisionModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
        </div>
        <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px; line-height: 1.5;">
            Deprovisioning will remove Device Owner kiosk lockdown, uninstall PisoPhone, and free up this seat slot.
        </p>
        
        <div style="display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px;">
            <label style="font-size: 12px; font-weight: 600; color: var(--text-main);">Admin PIN</label>
            <input type="text" id="deprov_pin_input" value="1234" class="form-control" style="font-family: monospace; font-size: 13px; padding: 8px 12px; border-radius: 8px; border: 1px solid var(--border); background: var(--input-bg); color: var(--text-main);">
        </div>

        <div id="deprov_log" style="display: block; background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 10px; font-family: monospace; font-size: 11px; color: var(--text-muted); height: 80px; overflow-y: auto; margin-bottom: 16px; white-space: pre-wrap;">Connect phone via USB and click Launch Utility or Execute.</div>

        <div style="display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap;">
            <button type="button" class="btn btn-outline" onclick="closeDeprovisionModal()">Close</button>
            <button type="button" class="btn btn-outline" style="border-color: rgba(225, 29, 72, 0.4); color: var(--danger);" onclick="window.open('https://pisophone.pages.dev/?mac=' + ESP32_MAC + '&ip=' + ESP32_HOST + '&slot=' + activeSlotNum + '&mode=deprovision', '_blank')">🌐 Web Deprovisioner</button>
            <button type="button" id="start_deprov_btn" class="btn btn-primary" style="background: linear-gradient(135deg, #e11d48 0%, #be123c 100%); border: none;" onclick="executeDeprovisionFlow()">⚡ Deprovision USB</button>
        </div>
    </div>
</div>

<!-- Unassigned Device Connection Request Pairing Modal -->
<div id="unassigned_pair_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.65); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
    <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 500px; width: 100%; box-shadow: var(--card-shadow);">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
            <h3 style="margin: 0; font-size: 18px; font-weight: 700;" id="unassigned_modal_title">⚡ Confirm Connection</h3>
            <button type="button" onclick="closeUnassignedPairModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
        </div>
        
        <div id="unassigned_modal_body" style="display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px;">
            <!-- Populated dynamically -->
        </div>

        <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid var(--border); margin-top: 16px; padding-top: 12px;">
            <button type="button" class="btn btn-outline" onclick="openInstallerForActiveSlot()" style="font-size: 12px; font-weight: 600;">📥 Open Web Installer</button>
            <button type="button" class="btn btn-outline" onclick="closeUnassignedPairModal()">Close</button>
        </div>
    </div>
</div>
)HTML";

#endif // WEB_DASHBOARD_MODALS_H
