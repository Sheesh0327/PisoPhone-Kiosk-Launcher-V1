#ifndef SUPER_ADMIN_TEMPLATE_H
#define SUPER_ADMIN_TEMPLATE_H

#include <Arduino.h>

const char SUPER_ADMIN_HTML[] PROGMEM = R"HTML(
        <!-- TAB 4: VENDOR SUPER ADMIN -->
        <div id="tab-superadmin" class="tab-content">
            <!-- Vendor Auth Gate (Shown if not yet authenticated in session) -->
            <div id="sa_auth_gate" class="card" style="max-width: 520px; margin: 20px auto; border-top: 4px solid #f59e0b;">
                <div class="card-header" style="text-align: center; justify-content: center;">
                    <div style="font-size: 32px; margin-bottom: 4px;">👑</div>
                    <h3 class="card-title" style="color: #f59e0b; font-size: 18px;">Vendor Super Admin Access</h3>
                </div>
                <p style="font-size: 13px; color: var(--text-muted); line-height: 1.5; text-align: center; margin-bottom: 20px;">
                    Exclusive access for Kiosk Vendors and Master Operators. Super Admin privileges grant coin vault retrieval authority, automatic revenue split calculation, and master settings management.
                </p>
                <div class="form-group">
                    <label>Super Admin Password</label>
                    <input type="password" id="sa_login_pw" placeholder="Enter Super Admin Password" onkeydown="if(event.key==='Enter') unlockSuperAdmin()">
                </div>
                <button type="button" class="btn" style="width: 100%; background: #f59e0b; color: #000; font-weight: 800; justify-content: center; margin-top: 8px;" onclick="unlockSuperAdmin()">
                    🔓 Unlock Super Admin Console
                </button>
            </div>

            <!-- Super Admin Main Console (Shown once unlocked) -->
            <div id="sa_main_console" style="display: none;">
                <!-- Master Privileges Status Banner -->
                <div class="card" style="background: linear-gradient(135deg, rgba(245, 158, 11, 0.12), rgba(16, 185, 129, 0.08)); border: 1px solid rgba(245, 158, 11, 0.3); margin-bottom: 20px;">
                    <div style="display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px;">
                        <div style="display: flex; align-items: center; gap: 12px;">
                            <span style="font-size: 28px;">👑</span>
                            <div>
                                <div style="font-size: 16px; font-weight: 800; color: #f59e0b;">Super Admin Active (Vendor Mode)</div>
                                <div style="font-size: 12px; color: var(--text-muted);">Full system authority enabled. Auto logout in <b id="sa_session_timer" style="color: #f59e0b; font-weight: 800; font-family: monospace;">05:00</b>.</div>
                            </div>
                        </div>
                        <button type="button" class="btn btn-outline btn-sm" onclick="lockSuperAdmin()" style="border-color: #f59e0b; color: #f59e0b;">
                            🔒 Exit Super Admin Mode
                        </button>
                    </div>
                </div>

                <div class="grid">
                    <!-- Revenue Split Calculator & Harvest Collection -->
                    <div class="card grid-full">
                        <div class="card-header">
                            <h3 class="card-title">📊 Revenue Split Calculator & Harvest</h3>
                            <span id="sa_vault_status_badge" class="badge" style="background: rgba(245, 158, 11, 0.2); color: #f59e0b; font-weight: 700; padding: 4px 10px; border-radius: 12px; font-size: 11px;">
                                🔒 MASKED (STANDBY)
                            </span>
                        </div>

                        <!-- Masked Display Box (Tappable) -->
                        <div id="sa_masked_box" onclick="promptUnmaskVault()" style="cursor: pointer; background: var(--input-bg); padding: 28px 20px; border-radius: var(--radius-lg); text-align: center; border: 2px dashed rgba(245, 158, 11, 0.4); margin-bottom: 16px; transition: transform 0.15s, border-color 0.2s;">
                            <div style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.75px; margin-bottom: 6px;">Revenue Split Calculations</div>
                            <div style="font-size: 38px; font-weight: 800; color: var(--text-muted); letter-spacing: 4px;">₱ • • • •</div>
                            <div style="margin-top: 10px; display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 700; color: #f59e0b; background: rgba(245, 158, 11, 0.15); padding: 6px 14px; border-radius: 20px;">
                                <span>👆 Tap to Unmask Split & Initiate Income Harvest</span>
                            </div>
                        </div>

                        <!-- Unmasked Display & Split Calculation Box -->
                        <div id="sa_unmasked_box" style="display: none;">
                            <div style="margin-bottom: 16px;">
                                <label style="display: flex; justify-content: space-between; font-weight: 600; font-size: 13px; margin-bottom: 8px;">
                                    <span>Split Ratio Presets:</span>
                                    <span id="sa_split_label" style="color: #f59e0b; font-weight: 800;">50% Vendor / 50% Operator</span>
                                </label>
                                <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-bottom: 12px;">
                                    <button type="button" class="btn btn-outline btn-sm" onclick="setSplitPercent(50)">50 / 50</button>
                                    <button type="button" class="btn btn-outline btn-sm" onclick="setSplitPercent(60)">60 / 40</button>
                                    <button type="button" class="btn btn-outline btn-sm" onclick="setSplitPercent(70)">70 / 30</button>
                                    <button type="button" class="btn btn-outline btn-sm" onclick="setSplitPercent(80)">80 / 20</button>
                                </div>
                                <div class="form-group">
                                    <label style="font-size: 12px;">Custom Vendor Share (%):</label>
                                    <input type="range" id="sa_split_slider" min="0" max="100" value="50" oninput="onSplitSliderChange(this.value)" style="width: 100%; accent-color: #f59e0b;">
                                </div>
                            </div>

                            <!-- Split Breakdown Cards -->
                            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 16px;">
                                <!-- Vendor Share Box -->
                                <div style="background: rgba(245, 158, 11, 0.08); border: 1px solid rgba(245, 158, 11, 0.3); border-radius: var(--radius-md); padding: 14px; text-align: center;">
                                    <div style="font-size: 11px; font-weight: 700; color: #f59e0b; text-transform: uppercase;">👑 Vendor Share (Payout)</div>
                                    <div id="sa_vendor_payout" style="font-size: 24px; font-weight: 800; color: #f59e0b; margin: 4px 0;">₱0.00</div>
                                    <div id="sa_vendor_share_sub" style="font-size: 11px; color: var(--text-muted);">50% of vault</div>
                                </div>
                                <!-- Operator Share Box -->
                                <div style="background: rgba(16, 185, 129, 0.08); border: 1px solid rgba(16, 185, 129, 0.3); border-radius: var(--radius-md); padding: 14px; text-align: center;">
                                    <div style="font-size: 11px; font-weight: 700; color: var(--primary); text-transform: uppercase;">🏪 Operator Share (Payout)</div>
                                    <div id="sa_operator_payout" style="font-size: 24px; font-weight: 800; color: var(--primary); margin: 4px 0;">₱0.00</div>
                                    <div id="sa_operator_share_sub" style="font-size: 11px; color: var(--text-muted);">50% of vault</div>
                                </div>
                            </div>

                            <!-- 5-Minute Auto-Reset Countdown Banner -->
                            <div style="margin-bottom: 16px; padding: 12px; background: rgba(239, 68, 68, 0.12); border: 1px solid rgba(239, 68, 68, 0.3); border-radius: var(--radius-md); text-align: left;">
                                <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px;">
                                    <span style="font-size: 12px; font-weight: 700; color: var(--danger);">⏳ Harvest Vault Auto-Reset Countdown</span>
                                    <span id="sa_countdown_timer" style="font-size: 15px; font-weight: 800; color: var(--danger); font-family: monospace;">05:00</span>
                                </div>
                                <div style="height: 6px; background: rgba(239, 68, 68, 0.2); border-radius: 3px; overflow: hidden;">
                                    <div id="sa_timer_bar" style="height: 100%; width: 100%; background: var(--danger); transition: width 1s linear;"></div>
                                </div>
                                <div style="font-size: 11px; color: var(--text-muted); margin-top: 6px; line-height: 1.3;">
                                    ⚠️ Unmasking initiates a 5-minute harvest window. The vault counter will automatically reset to ₱0 after 5 minutes (or upon logging out) to verify physical collection.
                                </div>
                            </div>

                            <div style="display: flex; gap: 8px;">
                                <button type="button" class="btn btn-outline btn-sm" style="flex: 1;" onclick="saveDefaultSplit()">
                                    💾 Save Default Split
                                </button>
                                <button type="button" class="btn btn-outline btn-sm" onclick="copySplitReceipt()">
                                    📋 Copy Receipt
                                </button>
                                <button type="button" class="btn btn-danger btn-sm" onclick="confirmResetVaultNow()">
                                    ✅ Finish Harvest & Reset Vault
                                </button>
                            </div>
                        </div>
                    </div>

                    <!-- Vendor Security Settings -->
                    <div class="card grid-full">
                        <div class="card-header">
                            <h3 class="card-title">🔐 Vendor Security & Credentials</h3>
                        </div>
                        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 16px;">
                            <div>
                                <label style="font-weight: 600; font-size: 13px;">Change Super Admin Password</label>
                                <div class="form-group" style="margin-top: 8px;">
                                    <input type="password" id="sa_current_pw" placeholder="Current Super Admin Password" style="margin-bottom: 8px;">
                                    <input type="password" id="sa_new_pw" placeholder="New Super Admin Password" style="margin-bottom: 8px;">
                                    <button type="button" class="btn" style="background: #f59e0b; color: #000; font-weight: 700;" onclick="changeSuperAdminPassword()">
                                        Update Password
                                    </button>
                                </div>
                            </div>
                            <div style="font-size: 12px; color: var(--text-muted); line-height: 1.5; background: var(--input-bg); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border);">
                                <b style="color: var(--text-main);">Super Admin Privilege Summary:</b><br>
                                • Authority to view masked vault and reset coin counter.<br>
                                • Automatic 5-minute retrieval safeguard.<br>
                                • Configurable revenue sharing split calculation.<br>
                                • Full access to all normal admin controls & terminal management.
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
)HTML";

const char SUPER_ADMIN_JS[] PROGMEM = R"JS(
        let saToken = localStorage.getItem('sa_token') || '';
        let saSessionExpiry = parseInt(localStorage.getItem('sa_session_expiry') || '0', 10);
        let saVaultTotal = 0;
        let saSessionTotal = 0;
        let saVendorSplit = 50;
        let saTimerInterval = null;
        let saSessionTimerInterval = null;
        let saRemainingSeconds = 0;

        function startSuperAdminSessionTimer() {
            if (saSessionTimerInterval) clearInterval(saSessionTimerInterval);
            
            const now = Date.now();
            if (!saSessionExpiry || saSessionExpiry <= now) {
                saSessionExpiry = now + (300 * 1000); // 5 minutes (300 seconds)
                localStorage.setItem('sa_session_expiry', saSessionExpiry.toString());
            }

            updateSessionTimerDisplay();

            saSessionTimerInterval = setInterval(() => {
                if (Date.now() >= saSessionExpiry) {
                    clearInterval(saSessionTimerInterval);
                    saSessionTimerInterval = null;
                    lockSuperAdmin();
                    alert('⏳ Super Admin session expired (5-minute limit reached). Logged out automatically.');
                } else {
                    updateSessionTimerDisplay();
                }
            }, 1000);
        }

        function updateSessionTimerDisplay() {
            const remainingSec = Math.max(0, Math.ceil((saSessionExpiry - Date.now()) / 1000));
            const m = Math.floor(remainingSec / 60);
            const s = remainingSec % 60;
            const str = (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
            const el = document.getElementById('sa_session_timer');
            if (el) el.textContent = str;
        }

        function unlockSuperAdmin() {
            const pw = document.getElementById('sa_login_pw').value || saToken;
            if (!pw) { alert('Please enter Super Admin password.'); return; }
            
            fetch('/api/superadmin/auth', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'super_admin_pw=' + encodeURIComponent(pw)
            })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'ok') {
                    saToken = pw;
                    localStorage.setItem('sa_token', pw);
                    document.getElementById('sa_auth_gate').style.display = 'none';
                    document.getElementById('sa_main_console').style.display = 'block';
                    saVaultTotal = data.total_coins || 0;
                    saSessionTotal = data.session_coins || 0;
                    saVendorSplit = data.vendor_split || 50;
                    setSplitPercent(saVendorSplit);
                    
                    startSuperAdminSessionTimer();

                    if (data.is_unmasked && data.remaining_seconds > 0) {
                        showUnmaskedVault(data.remaining_seconds);
                    } else {
                        showMaskedVault();
                    }
                } else {
                    lockSuperAdmin();
                    alert('❌ ' + (data.message || 'Incorrect Super Admin password.'));
                }
            })
            .catch(err => alert('Auth error: ' + err));
        }

        function lockSuperAdmin() {
            saToken = '';
            saSessionExpiry = 0;
            localStorage.removeItem('sa_token');
            localStorage.removeItem('sa_session_expiry');
            if (saTimerInterval) clearInterval(saTimerInterval);
            if (saSessionTimerInterval) clearInterval(saSessionTimerInterval);
            saTimerInterval = null;
            saSessionTimerInterval = null;
            document.getElementById('sa_auth_gate').style.display = 'block';
            document.getElementById('sa_main_console').style.display = 'none';
            document.getElementById('sa_login_pw').value = '';
        }

        function promptUnmaskVault() {
            if (!saToken) { alert('Please authenticate first.'); return; }
            
            const confirmed = confirm(
                '⚠️ INITIATE COIN RETRIEVAL?\n\n' +
                'Unmasking the vault initiates a 5-minute retrieval window.\n' +
                'The coin vault counter will automatically reset to ₱0 after 5 minutes (or upon logout) to verify physical collection.\n\n' +
                'Do you want to proceed?'
            );
            
            if (!confirmed) return;
            
            fetch('/api/superadmin/unmask', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'super_admin_pw=' + encodeURIComponent(saToken)
            })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'ok') {
                    saVaultTotal = data.total_coins || 0;
                    saSessionTotal = data.session_coins || 0;
                    saVendorSplit = data.vendor_split || saVendorSplit;
                    showUnmaskedVault(data.timeout_seconds || 300);
                    calculateSplit();
                } else {
                    alert('❌ ' + (data.message || 'Failed to unmask vault.'));
                }
            })
            .catch(err => alert('Unmask error: ' + err));
        }

        function showMaskedVault() {
            document.getElementById('sa_masked_box').style.display = 'block';
            document.getElementById('sa_unmasked_box').style.display = 'none';
            document.getElementById('sa_vault_status_badge').textContent = '🔒 MASKED (STANDBY)';
            document.getElementById('sa_vault_status_badge').style.background = 'rgba(245, 158, 11, 0.2)';
            document.getElementById('sa_vault_status_badge').style.color = '#f59e0b';
            if (saTimerInterval) clearInterval(saTimerInterval);
        }

        function showUnmaskedVault(secondsRemaining) {
            document.getElementById('sa_masked_box').style.display = 'none';
            document.getElementById('sa_unmasked_box').style.display = 'block';
            document.getElementById('sa_vault_status_badge').textContent = '🔓 UNMASKED (ACTIVE)';
            document.getElementById('sa_vault_status_badge').style.background = 'rgba(239, 68, 68, 0.2)';
            document.getElementById('sa_vault_status_badge').style.color = 'var(--danger)';
            
            saRemainingSeconds = secondsRemaining;
            startCountdownTimer();
            calculateSplit();
        }

        function startCountdownTimer() {
            if (saTimerInterval) clearInterval(saTimerInterval);
            updateTimerDisplay();
            
            saTimerInterval = setInterval(() => {
                saRemainingSeconds--;
                if (saRemainingSeconds <= 0) {
                    clearInterval(saTimerInterval);
                    alert('⏳ 5-Minute retrieval window expired. Coin vault counter has reset to ₱0.');
                    saVaultTotal = 0;
                    saSessionTotal = 0;
                    showMaskedVault();
                    calculateSplit();
                    window.location.reload();
                } else {
                    updateTimerDisplay();
                }
            }, 1000);
        }

        function updateTimerDisplay() {
            const m = Math.floor(saRemainingSeconds / 60);
            const s = saRemainingSeconds % 60;
            const str = (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
            const el = document.getElementById('sa_countdown_timer');
            if (el) el.textContent = str;
            
            const bar = document.getElementById('sa_timer_bar');
            if (bar) {
                const pct = (saRemainingSeconds / 300) * 100;
                bar.style.width = Math.max(0, Math.min(100, pct)) + '%';
            }
        }

        function confirmResetVaultNow() {
            if (!confirm('✅ Finish coin collection and reset vault counter to ₱0 now?')) return;
            
            fetch('/api/superadmin/reset_vault', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'super_admin_pw=' + encodeURIComponent(saToken)
            })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'ok') {
                    alert('✅ Coin collection completed! Vault reset to ₱0.');
                    saVaultTotal = 0;
                    saSessionTotal = 0;
                    showMaskedVault();
                    calculateSplit();
                    window.location.reload();
                } else {
                    alert('❌ ' + (data.message || 'Reset failed.'));
                }
            })
            .catch(err => alert('Reset error: ' + err));
        }

        function setSplitPercent(val) {
            saVendorSplit = parseInt(val, 10);
            const slider = document.getElementById('sa_split_slider');
            if (slider) slider.value = saVendorSplit;
            onSplitSliderChange(saVendorSplit);
        }

        function onSplitSliderChange(val) {
            saVendorSplit = parseInt(val, 10);
            const opSplit = 100 - saVendorSplit;
            const lbl = document.getElementById('sa_split_label');
            if (lbl) lbl.textContent = saVendorSplit + '% Vendor / ' + opSplit + '% Operator';
            calculateSplit();
        }

        function calculateSplit() {
            const opSplit = 100 - saVendorSplit;
            const vendorPayout = (saVaultTotal * (saVendorSplit / 100.0)).toFixed(2);
            const operatorPayout = (saVaultTotal * (opSplit / 100.0)).toFixed(2);
            
            const vpEl = document.getElementById('sa_vendor_payout');
            if (vpEl) vpEl.textContent = '₱' + vendorPayout;
            
            const opEl = document.getElementById('sa_operator_payout');
            if (opEl) opEl.textContent = '₱' + operatorPayout;
            
            const vsSub = document.getElementById('sa_vendor_share_sub');
            if (vsSub) vsSub.textContent = saVendorSplit + '% of vault';
            
            const osSub = document.getElementById('sa_operator_share_sub');
            if (osSub) osSub.textContent = opSplit + '% of vault';
        }

        function saveDefaultSplit() {
            if (!saToken) { alert('Please authenticate first.'); return; }
            
            fetch('/api/superadmin/save_split', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'super_admin_pw=' + encodeURIComponent(saToken) + '&vendor_split=' + saVendorSplit
            })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'ok') alert('✅ Default split saved: ' + saVendorSplit + '% Vendor');
                else alert('❌ ' + (data.message || 'Failed to save split.'));
            })
            .catch(err => alert('Error: ' + err));
        }

        function copySplitReceipt() {
            const opSplit = 100 - saVendorSplit;
            const vendorPayout = (saVaultTotal * (saVendorSplit / 100.0)).toFixed(2);
            const operatorPayout = (saVaultTotal * (opSplit / 100.0)).toFixed(2);
            const d = new Date().toLocaleString();
            
            const text = '==============================\n' +
                         '🧾 PISOPHONE REVENUE COLLECTION\n' +
                         '==============================\n' +
                         'Date/Time: ' + d + '\n' +
                         'Total Vault Collected: ₱' + saVaultTotal + '\n' +
                         '------------------------------\n' +
                         '👑 Vendor Share (' + saVendorSplit + '%): ₱' + vendorPayout + '\n' +
                         '🏪 Operator Share (' + opSplit + '%): ₱' + operatorPayout + '\n' +
                         '==============================';
            
            navigator.clipboard.writeText(text).then(() => {
                alert('📋 Payout receipt copied to clipboard!\n\n' + text);
            }).catch(() => {
                prompt('Copy receipt below:', text);
            });
        }

        function changeSuperAdminPassword() {
            const cur = document.getElementById('sa_current_pw').value;
            const nw = document.getElementById('sa_new_pw').value;
            if (!cur || !nw) { alert('Please fill in both password fields.'); return; }
            
            fetch('/api/superadmin/change_pw', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'current_pw=' + encodeURIComponent(cur) + '&new_pw=' + encodeURIComponent(nw)
            })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'ok') {
                    alert('✅ Super Admin password updated successfully!');
                    saToken = nw;
                    localStorage.setItem('sa_token', nw);
                    document.getElementById('sa_current_pw').value = '';
                    document.getElementById('sa_new_pw').value = '';
                } else {
                    alert('❌ ' + (data.message || 'Failed to update password.'));
                }
            })
            .catch(err => alert('Error: ' + err));
        }

        // Auto-check on page load if token is stored and session is valid (<= 5 minutes)
        window.addEventListener('DOMContentLoaded', () => {
            if (saToken) {
                if (saSessionExpiry && Date.now() >= saSessionExpiry) {
                    lockSuperAdmin();
                    alert('⏳ Super Admin session expired after 5 minutes. Logged out automatically.');
                } else {
                    document.getElementById('sa_login_pw').value = saToken;
                    unlockSuperAdmin();
                }
            }
        });
)JS";

#endif // SUPER_ADMIN_TEMPLATE_H
