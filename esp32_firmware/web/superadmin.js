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
                    notify('Vendor mode locked itself after 5 minutes.');
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
            if (!pw) { notify('Enter the vendor password.', 'bad'); return; }
            
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
                    document.getElementById('sa_auth_gate').classList.add('hidden');
                    document.getElementById('sa_main_console').classList.remove('hidden');
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
                    notify(data.message || 'That vendor password is not correct.', 'bad');
                }
            })
            .catch(err => notify('Could not reach the box: ' + err, 'bad'));
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
            document.getElementById('sa_auth_gate').classList.remove('hidden');
            document.getElementById('sa_main_console').classList.add('hidden');
            document.getElementById('sa_login_pw').value = '';
        }

        function promptUnmaskVault() {
            if (!saToken) { notify('Unlock vendor mode first.', 'bad'); return; }
            
            const confirmed = confirm(
                'Start collecting revenue?\n\n' +
                'This shows the revenue counter and opens a 5-minute window. ' +
                'The counter resets to ₱0 when the window ends or you lock the page, to confirm the coins were taken out.'
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
                    notify(data.message || 'Could not show the revenue.', 'bad');
                }
            })
            .catch(err => notify('Could not reach the box: ' + err, 'bad'));
        }

        function showMaskedVault() {
            document.getElementById('sa_masked_box').classList.remove('hidden');
            document.getElementById('sa_unmasked_box').classList.add('hidden');
            const badge = document.getElementById('sa_vault_status_badge');
            badge.textContent = 'Hidden';
            badge.className = 'tag warn';
            if (saTimerInterval) clearInterval(saTimerInterval);
        }

        function showUnmaskedVault(secondsRemaining) {
            document.getElementById('sa_masked_box').classList.add('hidden');
            document.getElementById('sa_unmasked_box').classList.remove('hidden');
            const badge = document.getElementById('sa_vault_status_badge');
            badge.textContent = 'Shown';
            badge.className = 'tag bad';
            
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
                    notify('The collection window ended. The revenue counter was reset to ₱0.');
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
            if (!confirm('Finish collecting and reset the revenue counter to ₱0 now?')) return;
            
            fetch('/api/superadmin/reset_vault', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'super_admin_pw=' + encodeURIComponent(saToken)
            })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'ok') {
                    notify('Collection finished. The revenue counter is back to ₱0.');
                    saVaultTotal = 0;
                    saSessionTotal = 0;
                    showMaskedVault();
                    calculateSplit();
                    window.location.reload();
                } else {
                    notify(data.message || 'Could not reset the counter.', 'bad');
                }
            })
            .catch(err => notify('Could not reach the box: ' + err, 'bad'));
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
            if (lbl) lbl.textContent = saVendorSplit + '% vendor / ' + opSplit + '% operator';
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
            if (vsSub) vsSub.textContent = saVendorSplit + '% of revenue';
            
            const osSub = document.getElementById('sa_operator_share_sub');
            if (osSub) osSub.textContent = opSplit + '% of revenue';
        }

        function saveDefaultSplit() {
            if (!saToken) { notify('Unlock vendor mode first.', 'bad'); return; }
            
            fetch('/api/superadmin/save_split', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'super_admin_pw=' + encodeURIComponent(saToken) + '&vendor_split=' + saVendorSplit
            })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'ok') notify('Default split saved: ' + saVendorSplit + '% vendor.');
                else notify(data.message || 'Could not save the split.', 'bad');
            })
            .catch(err => notify('Could not reach the box: ' + err, 'bad'));
        }

        function copySplitReceipt() {
            const opSplit = 100 - saVendorSplit;
            const vendorPayout = (saVaultTotal * (saVendorSplit / 100.0)).toFixed(2);
            const operatorPayout = (saVaultTotal * (opSplit / 100.0)).toFixed(2);
            const d = new Date().toLocaleString();
            
            const text = 'PISOPHONE REVENUE COLLECTION\n' +
                         '----------------------------\n' +
                         'Date/time: ' + d + '\n' +
                         'Total collected: ₱' + saVaultTotal + '\n' +
                         'Vendor share (' + saVendorSplit + '%): ₱' + vendorPayout + '\n' +
                         'Operator share (' + opSplit + '%): ₱' + operatorPayout;
            
            navigator.clipboard.writeText(text).then(() => {
                notify('Receipt copied.');
            }).catch(() => {
                prompt('Copy receipt below:', text);
            });
        }

        // Auto-check on page load if token is stored and session is valid (<= 5 minutes)
        window.addEventListener('DOMContentLoaded', () => {
            if (saToken) {
                if (saSessionExpiry && Date.now() >= saSessionExpiry) {
                    lockSuperAdmin();
                    notify('Vendor mode locked itself after 5 minutes.');
                } else {
                    document.getElementById('sa_login_pw').value = saToken;
                    unlockSuperAdmin();
                }
            }
        });
