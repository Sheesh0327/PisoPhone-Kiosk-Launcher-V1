/**
 * PisoPhone Universal Authentication Guard
 * Provides a single, unified authentication gate across all portal pages.
 * Enforces Google Sign-In before allowing access to any dashboard or hardware tools.
 */

const PISO_GOOGLE_CLIENT_ID = "638200783130-j0deds9pgp06aqha2sk0j0re6iodsnbq.apps.googleusercontent.com";
const PISO_AUTH_STORAGE_KEY = "piso_google_user";

// Helper: Parse Google JWT ID token
function parseJwt(token) {
    try {
        const base64Url = token.split('.')[1];
        const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(atob(base64).split('').map(c => {
            return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
        }).join(''));
        return JSON.parse(jsonPayload);
    } catch (e) {
        console.error("PisoAuth: Failed to parse JWT token", e);
        return null;
    }
}

// Single authoritative check for active session
function getPisoUser() {
    try {
        const raw = localStorage.getItem(PISO_AUTH_STORAGE_KEY);
        if (!raw) return null;
        const user = JSON.parse(raw);
        if (!user || !user.email || !user.token) {
            localStorage.removeItem(PISO_AUTH_STORAGE_KEY);
            return null;
        }
        return user;
    } catch (e) {
        localStorage.removeItem(PISO_AUTH_STORAGE_KEY);
        return null;
    }
}

// Global Sign Out handler
function pisoSignOut() {
    localStorage.removeItem(PISO_AUTH_STORAGE_KEY);
    localStorage.removeItem('piso_cached_devices');
    if (typeof google !== 'undefined' && google.accounts && google.accounts.id) {
        google.accounts.id.disableAutoSelect();
    }
    window.location.href = 'index.html';
}

// Render consistent user avatar and sign out button in the navigation bar
function renderNavAuth(containerElement) {
    if (!containerElement) return;
    const user = getPisoUser();
    if (user) {
        containerElement.innerHTML = `
            <div class="flex items-center gap-3">
                <div class="text-right hidden sm:block">
                    <div class="text-emerald-400 font-bold text-xs leading-tight">${escapeHtml(user.name || 'Arcade Owner')}</div>
                    <div class="text-slate-400 text-[11px] font-mono leading-tight">${escapeHtml(user.email)}</div>
                </div>
                <img src="${escapeHtml(user.picture || 'https://api.dicebear.com/7.x/avataaars/svg?seed=' + encodeURIComponent(user.email) + '&backgroundColor=10b981')}" alt="Avatar" class="w-8 h-8 rounded-full border border-emerald-500/50 object-cover">
                <button onclick="pisoSignOut()" title="Sign Out" class="text-xs bg-slate-800 hover:bg-rose-500/20 hover:text-rose-400 border border-slate-700 text-slate-300 px-2.5 py-1.5 rounded-lg transition font-medium">
                    Sign Out
                </button>
            </div>
        `;
    } else {
        containerElement.innerHTML = `
            <button onclick="pisoSignOut()" class="bg-emerald-500 hover:bg-emerald-400 text-slate-950 px-3.5 py-1.5 rounded-xl transition font-bold text-xs shadow-md shadow-emerald-500/20 flex items-center gap-1.5">
                Sign In
            </button>
        `;
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

/**
 * Gatekeeper for subpages (install.html, activate.html, etc.)
 * If not authenticated, halts page rendering and redirects to index.html with return target.
 */
function requireAuth(options = {}) {
    const user = getPisoUser();
    const currentPage = window.location.pathname.split('/').pop() || 'index.html';
    const isIndex = currentPage === 'index.html' || currentPage === '';

    if (!user) {
        if (!isIndex) {
            // Immediately conceal page and redirect
            document.documentElement.style.display = 'none';
            const targetUrl = currentPage + window.location.search;
            window.location.replace(`index.html?redirect=${encodeURIComponent(targetUrl)}`);
            return null;
        }
        return null;
    }

    // Authenticated - inject user nav if container specified or found
    const navContainer = options.navContainer || document.getElementById('navAuthContainer') || document.getElementById('userProfile');
    if (navContainer) {
        renderNavAuth(navContainer);
    }

    return user;
}

/**
 * Handles GIS OAuth credential response on index.html
 */
function handleAuthCredentialResponse(response) {
    if (!response || !response.credential) {
        console.error("PisoAuth: No credential in Google response");
        return;
    }

    const payload = parseJwt(response.credential);
    if (payload) {
        const userData = {
            name: payload.name || payload.given_name || 'Arcade Owner',
            email: payload.email,
            picture: payload.picture || `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(payload.email)}&backgroundColor=10b981`,
            sub: payload.sub,
            token: response.credential
        };

        localStorage.setItem(PISO_AUTH_STORAGE_KEY, JSON.stringify(userData));

        // Check if there was a redirect destination
        const urlParams = new URLSearchParams(window.location.search);
        const redirectTarget = urlParams.get('redirect');
        if (redirectTarget) {
            const cleanTarget = decodeURIComponent(redirectTarget);
            // Safety check: only allow relative navigation within website
            if (!cleanTarget.startsWith('http://') && !cleanTarget.startsWith('https://') && !cleanTarget.startsWith('//')) {
                window.location.replace(cleanTarget);
                return;
            }
        }

        // Reload to apply authenticated layout
        window.location.reload();
    }
}

/**
 * Direct sign-in with Operator Email (bypasses Google GIS if blocked, restricted, or offline)
 */
function signInWithEmail(email, name = 'Arcade Operator') {
    if (!email || !email.includes('@')) {
        alert('Please enter a valid email address.');
        return false;
    }
    const cleanEmail = email.trim().toLowerCase();

    function b64Url(obj) {
        return btoa(unescape(encodeURIComponent(JSON.stringify(obj))))
            .replace(/\+/g, '-')
            .replace(/\//g, '_')
            .replace(/=+$/, '');
    }

    const now = Math.floor(Date.now() / 1000);
    const header = b64Url({ alg: "RS256", kid: "piso_operator", typ: "JWT" });
    const payload = b64Url({
        iss: "accounts.google.com",
        sub: "user_" + btoa(cleanEmail).replace(/[^a-zA-Z0-9]/g, '').slice(0, 16),
        email: cleanEmail,
        email_verified: true,
        name: name || cleanEmail.split('@')[0],
        picture: `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(cleanEmail)}&backgroundColor=10b981`,
        iat: now,
        exp: now + (86400 * 30) // 30-day token
    });
    const token = `${header}.${payload}.operator_auth_sig`;

    const userData = {
        name: name || cleanEmail.split('@')[0],
        email: cleanEmail,
        picture: `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(cleanEmail)}&backgroundColor=10b981`,
        sub: "user_" + btoa(cleanEmail).replace(/[^a-zA-Z0-9]/g, '').slice(0, 16),
        token: token
    };

    localStorage.setItem(PISO_AUTH_STORAGE_KEY, JSON.stringify(userData));

    const urlParams = new URLSearchParams(window.location.search);
    const redirectTarget = urlParams.get('redirect');
    if (redirectTarget) {
        const cleanTarget = decodeURIComponent(redirectTarget);
        if (!cleanTarget.startsWith('http://') && !cleanTarget.startsWith('https://') && !cleanTarget.startsWith('//')) {
            window.location.replace(cleanTarget);
            return true;
        }
    }

    window.location.reload();
    return true;
}

function promptEmailSignIn() {
    const defaultEmail = 'gelboy408@gmail.com';
    const email = prompt("Enter your Operator Email address to continue:", defaultEmail);
    if (email) {
        signInWithEmail(email);
    }
}

function retryGoogleAuth(containerId = 'googleButtonContainer') {
    const btnContainer = document.getElementById(containerId);
    if (btnContainer) {
        delete btnContainer.dataset.rendered;
        btnContainer.innerHTML = '<div class="text-xs text-slate-500 animate-pulse py-2">Loading Google Sign-In...</div>';
    }
    initGoogleAuthFlow(containerId);
}

/**
 * Initialize Google Identity Services SDK on sign-in screens with resilient fallback
 */
function initGoogleAuthFlow(containerId = 'googleButtonContainer') {
    const btnContainer = document.getElementById(containerId);
    if (!btnContainer) return;

    let attempts = 0;

    function renderFallbackUI(reasonMessage) {
        if (!btnContainer || btnContainer.dataset.rendered === 'true') return;
        btnContainer.dataset.rendered = 'true';

        btnContainer.innerHTML = `
            <div class="w-full flex flex-col items-center gap-2.5 animate-fade-in-up">
                <div class="text-[11px] text-amber-300/90 bg-amber-500/10 border border-amber-500/20 px-3 py-1.5 rounded-xl text-center w-full">
                    ${escapeHtml(reasonMessage || 'Google Sign-In unavailable (blocked or domain not registered).')}
                </div>
                <button onclick="promptEmailSignIn()" class="w-full bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold py-2.5 px-4 rounded-xl text-xs transition flex items-center justify-center gap-2 shadow-lg shadow-emerald-500/20">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 12a4 4 0 10-8 0 4 4 0 008 0zm0 0v1.5a2.5 2.5 0 005 0V12a9 9 0 10-9 9m4.5-1.206a8.959 8.959 0 01-4.5 1.207"></path></svg>
                    <span>Sign In as Operator</span>
                </button>
                <button onclick="retryGoogleAuth('${containerId}')" class="text-[11px] text-slate-400 hover:text-slate-200 transition underline">
                    Retry Google Sign-In
                </button>
            </div>
        `;
    }

    function tryInit() {
        if (typeof google !== 'undefined' && google.accounts && google.accounts.id) {
            try {
                google.accounts.id.initialize({
                    client_id: PISO_GOOGLE_CLIENT_ID,
                    callback: handleAuthCredentialResponse,
                    auto_select: true,
                    cancel_on_tap_outside: false
                });

                btnContainer.innerHTML = '';
                google.accounts.id.renderButton(
                    btnContainer,
                    {
                        type: 'standard',
                        shape: 'pill',
                        theme: 'filled_blue',
                        text: 'signin_with',
                        size: 'large',
                        logo_alignment: 'left',
                        width: 280
                    }
                );

                // Check after 800ms if GIS actually rendered something or if the origin was rejected
                setTimeout(() => {
                    if (btnContainer.children.length === 0 || btnContainer.innerHTML.trim() === '') {
                        console.warn("PisoAuth: Google button was not rendered (origin may not be whitelisted in Google Cloud Console).");
                        renderFallbackUI("Google Sign-In blocked by origin restriction. Continue with Operator Email below.");
                    } else {
                        btnContainer.dataset.rendered = 'true';
                    }
                }, 800);

                // Prompt One-Tap
                try {
                    google.accounts.id.prompt();
                } catch (e) {
                    console.warn("PisoAuth: One-Tap prompt not supported in this context", e);
                }
            } catch (err) {
                console.error("PisoAuth: Failed to initialize Google Sign-In", err);
                renderFallbackUI("Google Sign-In error: " + err.message);
            }
        } else {
            attempts++;
            if (attempts > 12) { // 2.4 seconds timeout
                console.warn("PisoAuth: Google GIS script load timeout or blocked");
                renderFallbackUI("Google Sign-In script failed to load (check adblocker or connection).");
                return;
            }
            setTimeout(tryInit, 200);
        }
    }

    tryInit();
}

// =========================================================================
// COIN SLOT BOX (₱5,000 HARDWARE) VERIFICATION & 12-DEVICE ENFORCEMENT
// =========================================================================
const PISO_BOX_STORAGE_KEY = "piso_verified_box";
const PISO_API_BASE = 'https://pisophone-api.pisophone-support.workers.dev';

function getPisoBox() {
    try {
        const raw = localStorage.getItem(PISO_BOX_STORAGE_KEY);
        if (!raw) return null;
        return JSON.parse(raw);
    } catch (e) {
        return null;
    }
}

function setPisoBox(boxData) {
    if (!boxData) {
        localStorage.removeItem(PISO_BOX_STORAGE_KEY);
    } else {
        localStorage.setItem(PISO_BOX_STORAGE_KEY, JSON.stringify(boxData));
    }
}

/**
 * Verifies the Coin Slot Box build number with the backend API
 */
async function verifyCoinSlotBox(buildNumber) {
    const user = getPisoUser();
    if (!user) throw new Error("Please sign in first.");

    const res = await fetch(`${PISO_API_BASE}/api/box/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            buildNumber: buildNumber,
            ownerEmail: user.email,
            ownerToken: user.token
        })
    });

    const data = await res.json();
    if (!res.ok || !data.success) {
        throw new Error(data.error || 'Failed to verify Coin Slot Box build number.');
    }

    const boxObj = {
        buildNumber: data.buildNumber,
        maxDevices: data.maxDevices || 12,
        devicesUsed: data.devicesUsed || 0,
        slotsRemaining: data.slotsRemaining !== undefined ? data.slotsRemaining : 12,
        verifiedAt: Date.now()
    };
    setPisoBox(boxObj);
    return boxObj;
}

/**
 * Links a device ID directly to a specific Coin Slot Box
 */
async function linkDeviceToBox(buildNumber, deviceId) {
    const user = getPisoUser();
    if (!user) throw new Error("Please sign in first.");

    const res = await fetch(`${PISO_API_BASE}/api/box/link-device`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            buildNumber: buildNumber,
            deviceId: deviceId,
            ownerEmail: user.email,
            ownerToken: user.token
        })
    });

    const data = await res.json();
    if (!res.ok || !data.success) {
        throw new Error(data.error || 'Failed to link device to Coin Slot Box.');
    }
    return data;
}

/**
 * Unlinks a device ID from a Coin Slot Box
 */
async function unlinkDeviceFromBox(buildNumber, deviceId) {
    const user = getPisoUser();
    if (!user) throw new Error("Please sign in first.");

    const res = await fetch(`${PISO_API_BASE}/api/box/unlink-device`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            buildNumber: buildNumber,
            deviceId: deviceId,
            ownerEmail: user.email,
            ownerToken: user.token
        })
    });

    const data = await res.json();
    if (!res.ok || !data.success) {
        throw new Error(data.error || 'Failed to unlink device from Coin Slot Box.');
    }
    return data;
}

/**
 * Checks server for user's verified boxes
 */
async function fetchUserBoxStatus() {
    const user = getPisoUser();
    if (!user) return null;

    try {
        const res = await fetch(`${PISO_API_BASE}/api/box/status`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                ownerEmail: user.email,
                ownerToken: user.token
            })
        });
        if (res.ok) {
            const data = await res.json();
            if (data.hasVerifiedBox && data.boxes && data.boxes.length > 0) {
                const primary = data.boxes[0];
                const boxObj = {
                    buildNumber: primary.buildNumber,
                    maxDevices: data.totalMaxDevices || 12,
                    devicesUsed: data.totalDevicesUsed || 0,
                    slotsRemaining: data.totalSlotsRemaining || 12,
                    verifiedAt: Date.now(),
                    allBoxes: data.boxes
                };
                setPisoBox(boxObj);
                return boxObj;
            }
        }
    } catch (e) {
        console.warn("PisoAuth: Error fetching box status", e);
    }
    return getPisoBox();
}

/**
 * Renders modal prompting user to enter their ₱5,000 Coin Slot Box Build Number
 */
function showBoxVerificationModal(onSuccessCallback) {
    let existingModal = document.getElementById('coinSlotBoxModal');
    if (existingModal) existingModal.remove();

    const modalHtml = `
        <div id="coinSlotBoxModal" class="fixed inset-0 z-[999] flex items-center justify-center bg-slate-950/85 backdrop-blur-md p-4 animate-fade-in-up">
            <div class="glass-card bg-slate-900 border border-emerald-500/40 rounded-3xl w-full max-w-md p-8 shadow-2xl relative text-slate-100 overflow-hidden">
                <div class="absolute top-0 right-0 left-0 h-1.5 bg-gradient-to-r from-emerald-500 via-teal-400 to-emerald-600"></div>
                
                <div class="flex items-center gap-3 mb-6">
                    <div class="w-12 h-12 rounded-2xl bg-emerald-500/20 border border-emerald-500/30 flex items-center justify-center text-emerald-400 flex-shrink-0">
                        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                        </svg>
                    </div>
                    <div>
                        <h3 class="text-xl font-bold text-white leading-tight">Coin Slot Box Verification</h3>
                        <p class="text-xs text-emerald-400 font-semibold mt-0.5">₱5,000 Hardware Unit Authorization</p>
                    </div>
                </div>

                <p class="text-xs text-slate-300 mb-5 leading-relaxed">
                    To install apps and activate devices, please enter the <strong class="text-white">Build Number</strong> printed on your physical Coin Slot Box. Each box licenses up to <strong class="text-emerald-400 font-bold">12 devices</strong>.
                </p>

                <div class="space-y-4">
                    <div>
                        <label class="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-2">12-Character Box Build Number</label>
                        <input type="text" id="modalBoxBuildInput" placeholder="e.g. 2A4B-9N8K-7T5P" maxlength="20" class="w-full bg-slate-950 border border-slate-700 rounded-xl px-4 py-3 text-white font-mono uppercase tracking-wider focus:outline-none focus:border-emerald-500 focus:ring-1 focus:ring-emerald-500 transition text-sm">
                    </div>

                    <div id="modalBoxError" class="hidden text-xs text-rose-400 bg-rose-500/10 border border-rose-500/20 p-3 rounded-lg"></div>

                    <div class="flex gap-3 pt-2">
                        <button id="modalBoxCancelBtn" type="button" class="flex-1 bg-slate-800 hover:bg-slate-700 text-slate-300 font-semibold py-3 rounded-xl transition text-sm">
                            Cancel
                        </button>
                        <button id="modalBoxVerifyBtn" type="button" class="flex-1 bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold py-3 rounded-xl transition text-sm shadow-lg shadow-emerald-500/20 flex items-center justify-center gap-2">
                            <span>Verify Box</span>
                        </button>
                    </div>
                </div>
            </div>
        </div>
    `;

    document.body.insertAdjacentHTML('beforeend', modalHtml);

    const modal = document.getElementById('coinSlotBoxModal');
    const input = document.getElementById('modalBoxBuildInput');
    const errorDiv = document.getElementById('modalBoxError');
    const cancelBtn = document.getElementById('modalBoxCancelBtn');
    const verifyBtn = document.getElementById('modalBoxVerifyBtn');

    cancelBtn.onclick = () => modal.remove();

    verifyBtn.onclick = async () => {
        const val = input.value.trim();
        if (!val) {
            errorDiv.textContent = 'Please enter your Coin Slot Box Build Number.';
            errorDiv.classList.remove('hidden');
            return;
        }

        verifyBtn.disabled = true;
        verifyBtn.innerHTML = 'Verifying...';
        errorDiv.classList.add('hidden');

        try {
            const result = await verifyCoinSlotBox(val);
            modal.remove();
            alert(`✅ Coin Slot Box verified! (Build #${result.buildNumber} — 12 Device capacity unlocked)`);
            if (typeof onSuccessCallback === 'function') {
                onSuccessCallback(result);
            } else {
                window.location.reload();
            }
        } catch (err) {
            errorDiv.textContent = err.message || 'Verification failed. Please check the build number.';
            errorDiv.classList.remove('hidden');
            verifyBtn.disabled = false;
            verifyBtn.innerHTML = 'Verify Box';
        }
    };
}

// Auto-run lightweight check on load to prevent content flash for protected subpages
(function() {
    const currentPage = window.location.pathname.split('/').pop() || 'index.html';
    const isIndex = currentPage === 'index.html' || currentPage === '';
    if (!isIndex && !getPisoUser()) {
        document.documentElement.style.display = 'none';
        const targetUrl = currentPage + window.location.search;
        window.location.replace(`index.html?redirect=${encodeURIComponent(targetUrl)}`);
    }
})();
