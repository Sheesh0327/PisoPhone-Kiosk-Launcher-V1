#ifndef SUPER_ADMIN_TEMPLATE_H
#define SUPER_ADMIN_TEMPLATE_H

#include <pgmspace.h>

static const char SUPER_ADMIN_HTML[] PROGMEM = R"HTML(
<div id="tab-superadmin" class="tab-content">
  <div class="card" style="border-top: 4px solid #8b5cf6;">
    <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px;">
      <div>
        <h3 style="margin: 0; color: #8b5cf6; display: flex; align-items: center; gap: 8px;">
          👑 Vendor & Super Admin Controls
        </h3>
        <p style="margin: 4px 0 0 0; font-size: 12px; color: var(--text-muted);">
          Protected operations: Master Vault unmasking, revenue split, and credential management.
        </p>
      </div>
      <div id="sa-status-badge" style="background: rgba(139, 92, 246, 0.15); color: #8b5cf6; font-size: 11px; font-weight: 700; padding: 4px 10px; border-radius: 20px; border: 1px solid rgba(139, 92, 246, 0.3);">
        🔒 Super Admin Locked
      </div>
    </div>

    <!-- Login / Unlock Section -->
    <div id="sa-login-section" style="background: var(--sub-bg); border: 1px solid var(--border); border-radius: 12px; padding: 20px; margin-bottom: 20px;">
      <h4 style="margin-top: 0; margin-bottom: 12px; font-size: 14px; font-weight: 700;">🔑 Authenticate Super Admin</h4>
      <div style="display: flex; gap: 10px; flex-wrap: wrap;">
        <input type="password" id="sa_password_input" placeholder="Enter Super Admin Password" style="flex: 1; min-width: 200px; padding: 10px 14px; background: var(--bg); border: 1px solid var(--border); color: var(--text-main); border-radius: 8px; font-size: 14px;">
        <button type="button" class="btn" onclick="authenticateSuperAdminUI()" style="background: #8b5cf6; color: white; padding: 10px 20px; border-radius: 8px; font-weight: 700;">Unlock Super Admin</button>
      </div>
      <div id="sa-login-msg" style="margin-top: 10px; font-size: 12px; font-weight: 600;"></div>
    </div>

    <!-- Authenticated Controls Container (Hidden until unlocked) -->
    <div id="sa-controls-section" style="display: none;">
      
      <!-- 5-Minute Unmask Vault Card -->
      <div style="background: linear-gradient(135deg, rgba(139, 92, 246, 0.08) 0%, rgba(59, 130, 246, 0.08) 100%); border: 1px solid rgba(139, 92, 246, 0.3); border-radius: 14px; padding: 20px; margin-bottom: 20px;">
        <div style="display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 12px;">
          <div>
            <h4 style="margin: 0; color: var(--text-main); font-size: 15px; font-weight: 800;">💰 Coin Vault Unmasking & Reset</h4>
            <p style="margin: 4px 0 12px 0; font-size: 12px; color: var(--text-muted); max-width: 500px;">
              Unmasking displays total coins & revenue counters. For security, an auto-reset timer triggers after <strong>5 minutes</strong> of unmasking.
            </p>
          </div>
          <div id="sa-timer-display" style="display: none; background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.3); font-family: monospace; font-size: 14px; font-weight: 800; padding: 6px 14px; border-radius: 8px;">
            ⏱️ Reset in 05:00
          </div>
        </div>

        <div id="sa-masked-box" style="background: var(--bg); border: 1px dashed var(--border); border-radius: 10px; padding: 16px; text-align: center; margin-bottom: 16px;">
          <div style="font-size: 28px; font-weight: 900; letter-spacing: 4px; color: var(--text-muted);">••••••••••</div>
          <div style="font-size: 11px; color: var(--text-muted); margin-top: 4px;">Vault Counters Masked (Protected)</div>
        </div>

        <div id="sa-unmasked-box" style="display: none; background: var(--bg); border: 1px solid rgba(16, 185, 129, 0.4); border-radius: 10px; padding: 16px; margin-bottom: 16px;">
          <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; text-align: center;">
            <div>
              <div style="font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase;">Total Coins Lifetime</div>
              <div id="sa-val-coins" style="font-size: 22px; font-weight: 800; color: var(--primary); margin-top: 2px;">0</div>
            </div>
            <div>
              <div style="font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase;">Session Coins</div>
              <div id="sa-val-session-coins" style="font-size: 22px; font-weight: 800; color: #3b82f6; margin-top: 2px;">0</div>
            </div>
            <div>
              <div style="font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase;">Total Earnings (₱)</div>
              <div id="sa-val-earnings" style="font-size: 22px; font-weight: 800; color: #10b981; margin-top: 2px;">₱0.00</div>
            </div>
          </div>
        </div>

        <div style="display: flex; gap: 10px; flex-wrap: wrap;">
          <button type="button" id="sa-unmask-btn" class="btn" onclick="unmaskVaultUI()" style="background: #3b82f6; color: white; padding: 10px 18px; border-radius: 8px; font-weight: 700; font-size: 13px;">👁️ Unmask Vault (Arm 5-Min Reset)</button>
          <button type="button" class="btn" onclick="resetVaultManualUI()" style="background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.4); padding: 10px 18px; border-radius: 8px; font-weight: 700; font-size: 13px;">🗑️ Manual Zero Reset</button>
        </div>
      </div>

      <!-- Vendor Revenue Split Configuration -->
      <div style="background: var(--sub-bg); border: 1px solid var(--border); border-radius: 12px; padding: 20px; margin-bottom: 20px;">
        <h4 style="margin-top: 0; margin-bottom: 6px; font-size: 14px; font-weight: 700;">📊 Vendor Revenue Split Percentage</h4>
        <p style="margin: 0 0 14px 0; font-size: 12px; color: var(--text-muted);">Set the percentage split allocated to the machine vendor / operator.</p>
        
        <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
          <div style="display: flex; align-items: center; gap: 6px; flex: 1; min-width: 200px;">
            <input type="number" id="sa_vendor_split_input" min="0" max="100" value="30" style="width: 80px; padding: 8px 12px; background: var(--bg); border: 1px solid var(--border); color: var(--text-main); border-radius: 8px; font-weight: 700; font-size: 15px; text-align: center;">
            <span style="font-weight: 700; font-size: 15px; color: var(--text-muted);">% Vendor Share</span>
          </div>
          <button type="button" class="btn" onclick="saveVendorSplitUI()" style="background: #10b981; color: white; padding: 8px 16px; border-radius: 8px; font-weight: 700; font-size: 13px;">💾 Save Split %</button>
        </div>
      </div>

      <!-- Change Super Admin Password -->
      <div style="background: var(--sub-bg); border: 1px solid var(--border); border-radius: 12px; padding: 20px;">
        <h4 style="margin-top: 0; margin-bottom: 6px; font-size: 14px; font-weight: 700;">🔐 Change Super Admin Password</h4>
        <p style="margin: 0 0 14px 0; font-size: 12px; color: var(--text-muted);">Update the master vendor password for hardware configuration access.</p>

        <div style="display: flex; gap: 10px; flex-wrap: wrap;">
          <input type="password" id="sa_new_pw_input" placeholder="New Super Admin Password" style="flex: 1; min-width: 200px; padding: 8px 12px; background: var(--bg); border: 1px solid var(--border); color: var(--text-main); border-radius: 8px; font-size: 13px;">
          <button type="button" class="btn" onclick="changeSuperAdminPwUI()" style="background: #8b5cf6; color: white; padding: 8px 16px; border-radius: 8px; font-weight: 700; font-size: 13px;">Update Password</button>
        </div>
      </div>

    </div>
  </div>
</div>
)HTML";

static const char SUPER_ADMIN_JS[] PROGMEM = R"JS(
<script>
let saUnmaskInterval = null;
let saSuperAdminPw = "";

function authenticateSuperAdminUI() {
  const pw = document.getElementById('sa_password_input').value.trim();
  const msgDiv = document.getElementById('sa-login-msg');
  if (!pw) {
    msgDiv.style.color = '#ef4444';
    msgDiv.textContent = 'Please enter password.';
    return;
  }
  
  fetch('/api/superadmin/auth', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'super_admin_pw=' + encodeURIComponent(pw)
  })
  .then(res => res.json())
  .then(data => {
    if (data.status === 'ok') {
      saSuperAdminPw = pw;
      msgDiv.style.color = '#10b981';
      msgDiv.textContent = 'Unlocked successfully!';
      document.getElementById('sa-status-badge').innerHTML = '🔓 Super Admin Active';
      document.getElementById('sa-status-badge').style.background = 'rgba(16, 185, 129, 0.15)';
      document.getElementById('sa-status-badge').style.color = '#10b981';
      document.getElementById('sa-controls-section').style.display = 'block';
      document.getElementById('sa_vendor_split_input').value = data.vendor_split || 30;
      
      if (data.is_unmasked) {
        showUnmaskedVaultData(data, data.remaining_seconds || 300);
      }
    } else {
      msgDiv.style.color = '#ef4444';
      msgDiv.textContent = data.message || 'Authentication failed.';
    }
  })
  .catch(err => {
    msgDiv.style.color = '#ef4444';
    msgDiv.textContent = 'Network error during authentication.';
  });
}

function unmaskVaultUI() {
  if (!saSuperAdminPw) return;
  fetch('/api/superadmin/unmask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'super_admin_pw=' + encodeURIComponent(saSuperAdminPw)
  })
  .then(res => res.json())
  .then(data => {
    if (data.status === 'ok') {
      showUnmaskedVaultData(data, data.timeout_seconds || 300);
    } else {
      alert(data.message || 'Failed to unmask vault.');
    }
  });
}

function showUnmaskedVaultData(data, timeoutSec) {
  document.getElementById('sa-masked-box').style.display = 'none';
  document.getElementById('sa-unmasked-box').style.display = 'block';
  document.getElementById('sa-val-coins').textContent = data.total_coins || 0;
  document.getElementById('sa-val-session-coins').textContent = data.session_coins || 0;
  document.getElementById('sa-val-earnings').textContent = '₱' + (data.total_earnings || (data.total_coins * 1.0)).toFixed(2);
  
  const timerDisplay = document.getElementById('sa-timer-display');
  timerDisplay.style.display = 'block';
  
  let remaining = timeoutSec;
  if (saUnmaskInterval) clearInterval(saUnmaskInterval);
  
  saUnmaskInterval = setInterval(() => {
    remaining--;
    if (remaining <= 0) {
      clearInterval(saUnmaskInterval);
      timerDisplay.style.display = 'none';
      document.getElementById('sa-unmasked-box').style.display = 'none';
      document.getElementById('sa-masked-box').style.display = 'block';
      alert('Vault 5-Minute timeout expired! Lifetime counters reset.');
    } else {
      const m = Math.floor(remaining / 60).toString().padStart(2, '0');
      const s = (remaining % 60).toString().padStart(2, '0');
      timerDisplay.textContent = '⏱️ Reset in ' + m + ':' + s;
    }
  }, 1000);
}

function resetVaultManualUI() {
  if (!saSuperAdminPw) return;
  if (!confirm('Are you sure you want to manually zero out all vault counters?')) return;
  fetch('/api/superadmin/reset_vault', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'super_admin_pw=' + encodeURIComponent(saSuperAdminPw)
  })
  .then(res => res.json())
  .then(data => {
    if (data.status === 'ok') {
      if (saUnmaskInterval) clearInterval(saUnmaskInterval);
      document.getElementById('sa-timer-display').style.display = 'none';
      document.getElementById('sa-unmasked-box').style.display = 'none';
      document.getElementById('sa-masked-box').style.display = 'block';
      alert('Vault counters successfully reset to 0.');
    }
  });
}

function saveVendorSplitUI() {
  if (!saSuperAdminPw) return;
  const split = document.getElementById('sa_vendor_split_input').value;
  fetch('/api/superadmin/save_split', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'super_admin_pw=' + encodeURIComponent(saSuperAdminPw) + '&vendor_split=' + encodeURIComponent(split)
  })
  .then(res => res.json())
  .then(data => {
    if (data.status === 'ok') {
      alert('Vendor split percentage updated to ' + data.vendor_split + '%.');
    }
  });
}

function changeSuperAdminPwUI() {
  if (!saSuperAdminPw) return;
  const newPw = document.getElementById('sa_new_pw_input').value.trim();
  if (!newPw) return alert('Enter a valid new password.');
  fetch('/api/superadmin/change_pw', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'super_admin_pw=' + encodeURIComponent(saSuperAdminPw) + '&new_pw=' + encodeURIComponent(newPw)
  })
  .then(res => res.json())
  .then(data => {
    if (data.status === 'ok') {
      saSuperAdminPw = newPw;
      document.getElementById('sa_new_pw_input').value = '';
      alert('Super Admin password successfully changed!');
    } else {
      alert(data.message || 'Failed to update password.');
    }
  });
}
</script>
)JS";

#endif // SUPER_ADMIN_TEMPLATE_H
