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
