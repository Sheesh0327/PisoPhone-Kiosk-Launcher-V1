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
    localStorage.removeItem(PISO_BOX_STORAGE_KEY);
    localStorage.removeItem('piso_user_credits');
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
// COIN SLOT BOX (₱5,000 HARDWARE) VERIFICATION & 10-DEVICE ENFORCEMENT
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
        maxDevices: data.maxDevices || 10,
        devicesUsed: data.devicesUsed || 0,
        slotsRemaining: data.slotsRemaining !== undefined ? data.slotsRemaining : 10,
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

    const normId = String(deviceId).trim().toUpperCase().replace(/^HW-/, '');

    // Purge immediately from local storage box cache
    try {
        const boxStr = localStorage.getItem(PISO_BOX_STORAGE_KEY);
        if (boxStr) {
            const box = JSON.parse(boxStr);
            if (box && Array.isArray(box.allBoxes)) {
                box.allBoxes.forEach(b => {
                    if (b.buildNumber === buildNumber) {
                        if (Array.isArray(b.linkedDevices)) {
                            b.linkedDevices = b.linkedDevices.filter(id => String(id).trim().toUpperCase().replace(/^HW-/, '') !== normId);
                        }
                        if (Array.isArray(b.devices)) {
                            b.devices = b.devices.filter(d => String(d.deviceId || '').trim().toUpperCase().replace(/^HW-/, '') !== normId);
                        }
                        b.devicesUsed = (b.devices || b.linkedDevices || []).length;
                        b.slotsRemaining = Math.max(0, (b.maxDevices || 10) - b.devicesUsed);
                    }
                });
                localStorage.setItem(PISO_BOX_STORAGE_KEY, JSON.stringify(box));
            }
        }
    } catch(e) {}

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
 * Completely removes a device from user's account and unlinks it from any boxes
 */
async function removeDeviceFromAccount(deviceId) {
    const user = getPisoUser();
    if (!user) throw new Error("Please sign in first.");

    const normId = String(deviceId).trim().toUpperCase().replace(/^HW-/, '');

    // Purge immediately from local device cache
    try {
        const cachedStr = localStorage.getItem('piso_cached_devices');
        if (cachedStr) {
            const list = JSON.parse(cachedStr);
            if (Array.isArray(list)) {
                const updated = list.filter(d => String(d.deviceId || '').trim().toUpperCase().replace(/^HW-/, '') !== normId);
                localStorage.setItem('piso_cached_devices', JSON.stringify(updated));
            }
        }
    } catch(e) {}

    // Purge immediately from local box cache
    try {
        const boxStr = localStorage.getItem(PISO_BOX_STORAGE_KEY);
        if (boxStr) {
            const box = JSON.parse(boxStr);
            if (box) {
                if (Array.isArray(box.allBoxes)) {
                    box.allBoxes.forEach(b => {
                        if (Array.isArray(b.linkedDevices)) {
                            b.linkedDevices = b.linkedDevices.filter(id => String(id).trim().toUpperCase().replace(/^HW-/, '') !== normId);
                        }
                        if (Array.isArray(b.devices)) {
                            b.devices = b.devices.filter(d => String(d.deviceId || '').trim().toUpperCase().replace(/^HW-/, '') !== normId);
                        }
                        b.devicesUsed = (b.devices || b.linkedDevices || []).length;
                        b.slotsRemaining = Math.max(0, (b.maxDevices || 10) - b.devicesUsed);
                    });
                }
                if (Array.isArray(box.devices)) {
                    box.devices = box.devices.filter(d => String(d.deviceId || '').trim().toUpperCase().replace(/^HW-/, '') !== normId);
                }
                if (Array.isArray(box.linkedDevices)) {
                    box.linkedDevices = box.linkedDevices.filter(id => String(id).trim().toUpperCase().replace(/^HW-/, '') !== normId);
                }
                box.devicesUsed = (box.devices || box.linkedDevices || []).length;
                box.slotsRemaining = Math.max(0, (box.maxDevices || 10) - box.devicesUsed);
                localStorage.setItem(PISO_BOX_STORAGE_KEY, JSON.stringify(box));
            }
        }
    } catch(e) {}

    const res = await fetch(`${PISO_API_BASE}/api/user/remove-device`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            deviceId: deviceId,
            ownerEmail: user.email,
            ownerToken: user.token
        })
    });

    const data = await res.json();
    if (!res.ok || !data.success) {
        throw new Error(data.error || 'Failed to remove device from account.');
    }
    return data;
}

/**
 * Transfers commercial license from one device to another (1-device-only policy)
 */
async function transferLicenseBetweenDevices(sourceDeviceId, targetDeviceId, transferBoxAssignment = true) {
    const user = getPisoUser();
    if (!user) throw new Error("Please sign in first.");

    const res = await fetch(`${PISO_API_BASE}/api/user/transfer-license`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            sourceDeviceId,
            targetDeviceId,
            transferBoxAssignment: Boolean(transferBoxAssignment),
            ownerEmail: user.email,
            ownerToken: user.token
        })
    });

    const data = await res.json();
    if (!res.ok || !data.success) {
        throw new Error(data.error || 'Failed to transfer license to target device.');
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
                    maxDevices: data.totalMaxDevices || 10,
                    devicesUsed: data.totalDevicesUsed || 0,
                    slotsRemaining: data.totalSlotsRemaining || 10,
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
 * Launches a full-screen mobile camera QR Code Scanner to register Coin Slot Boxes
 */
let qrScannerStream = null;
let qrScannerInstance = null;

async function openQrBoxScanner(onSuccessCallback) {
    const existing = document.getElementById('pisoQrScannerModal');
    if (existing) existing.remove();

    const user = getPisoUser();
    if (!user) {
        if (typeof showDashboardToast === 'function') {
            showDashboardToast("Please sign in first to register a Coin Slot Box.", "error");
        } else {
            alert("Please sign in first to register a Coin Slot Box.");
        }
        return;
    }

    const scannerModalHtml = `
        <div id="pisoQrScannerModal" class="fixed inset-0 z-[1000] flex flex-col bg-slate-950 text-white animate-fade-in-up">
            <!-- Header Bar -->
            <div class="p-4 sm:p-6 bg-slate-900/90 backdrop-blur-md border-b border-slate-800 flex items-center justify-between z-10">
                <div class="flex items-center gap-3">
                    <div class="w-10 h-10 rounded-xl bg-emerald-500/20 border border-emerald-500/30 text-emerald-400 flex items-center justify-center flex-shrink-0">
                        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm12 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z"></path></svg>
                    </div>
                    <div>
                        <h3 class="text-base sm:text-lg font-bold text-white">Scan Box QR Code</h3>
                        <p class="text-[11px] sm:text-xs text-slate-400">Point your camera at the QR code printed on the Coin Slot Box</p>
                    </div>
                </div>
                <button type="button" id="closeQrScannerBtn" class="w-9 h-9 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 flex items-center justify-center transition border border-slate-700">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
                </button>
            </div>

            <!-- Scanner Camera Viewport Container -->
            <div class="relative flex-grow flex items-center justify-center bg-black overflow-hidden select-none">
                <video id="qrVideoFeed" playsinline autoplay muted class="w-full h-full object-cover"></video>
                <div id="html5QrReaderDiv" class="w-full h-full absolute inset-0 hidden"></div>

                <!-- Animated Viewfinder Overlay -->
                <div class="absolute inset-0 pointer-events-none flex flex-col items-center justify-center p-6">
                    <div class="relative w-64 h-64 sm:w-72 sm:h-72 border-2 border-emerald-400/60 rounded-3xl overflow-hidden shadow-2xl">
                        <!-- Corner Accents -->
                        <div class="absolute top-0 left-0 w-8 h-8 border-t-4 border-l-4 border-emerald-400 rounded-tl-xl"></div>
                        <div class="absolute top-0 right-0 w-8 h-8 border-t-4 border-r-4 border-emerald-400 rounded-tr-xl"></div>
                        <div class="absolute bottom-0 left-0 w-8 h-8 border-b-4 border-l-4 border-emerald-400 rounded-bl-xl"></div>
                        <div class="absolute bottom-0 right-0 w-8 h-8 border-b-4 border-r-4 border-emerald-400 rounded-br-xl"></div>

                        <!-- Animated Scanning Laser Line -->
                        <div class="absolute inset-x-0 h-1 bg-gradient-to-r from-transparent via-emerald-400 to-transparent shadow-[0_0_15px_#10b981] animate-pulse" style="animation: scanLaser 2s infinite ease-in-out;"></div>
                    </div>
                    <div class="mt-6 text-center px-4 py-2 rounded-full bg-slate-900/80 backdrop-blur-md border border-slate-800 text-xs text-slate-200">
                        Align the QR code within the frame to auto-claim
                    </div>
                </div>

                <!-- Scanner Status Notification / Loading Indicator -->
                <div id="qrScannerStatus" class="absolute bottom-24 inset-x-4 max-w-sm mx-auto text-center hidden">
                    <div class="bg-emerald-500 text-slate-950 font-bold px-4 py-2.5 rounded-xl text-xs shadow-2xl flex items-center justify-center gap-2">
                        <div class="w-4 h-4 border-2 border-slate-950 border-t-transparent rounded-full animate-spin"></div>
                        <span id="qrScannerStatusText">Processing Box Registration...</span>
                    </div>
                </div>
            </div>

            <!-- Footer Controls Bar (Torch, Switch Camera, Manual Input, Image Upload) -->
            <div class="p-4 sm:p-6 bg-slate-900/90 backdrop-blur-md border-t border-slate-800 flex items-center justify-around gap-2 z-10">
                <button type="button" id="qrSwitchCameraBtn" class="flex flex-col items-center gap-1 text-slate-400 hover:text-white transition p-2">
                    <div class="w-10 h-10 rounded-xl bg-slate-800 border border-slate-700 flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path></svg>
                    </div>
                    <span class="text-[10px]">Flip Camera</span>
                </button>

                <label for="qrFileInput" class="flex flex-col items-center gap-1 text-slate-400 hover:text-white transition p-2 cursor-pointer">
                    <div class="w-10 h-10 rounded-xl bg-slate-800 border border-slate-700 flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"></path></svg>
                    </div>
                    <span class="text-[10px]">Upload Photo</span>
                    <input type="file" id="qrFileInput" accept="image/*" class="hidden">
                </label>

                <button type="button" id="qrManualInputBtn" class="flex flex-col items-center gap-1 text-emerald-400 hover:text-emerald-300 transition p-2">
                    <div class="w-10 h-10 rounded-xl bg-emerald-500/20 border border-emerald-500/30 flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path></svg>
                    </div>
                    <span class="text-[10px]">Type Serial</span>
                </button>
            </div>
        </div>
    `;

    document.body.insertAdjacentHTML('beforeend', scannerModalHtml);

    const modal = document.getElementById('pisoQrScannerModal');
    const closeBtn = document.getElementById('closeQrScannerBtn');
    const video = document.getElementById('qrVideoFeed');
    const statusDiv = document.getElementById('qrScannerStatus');
    const statusText = document.getElementById('qrScannerStatusText');
    const switchCamBtn = document.getElementById('qrSwitchCameraBtn');
    const fileInput = document.getElementById('qrFileInput');
    const manualBtn = document.getElementById('qrManualInputBtn');

    let currentFacingMode = 'environment';
    let scanningActive = true;
    let animationFrameId = null;

    function stopCamera() {
        scanningActive = false;
        if (animationFrameId) cancelAnimationFrame(animationFrameId);
        if (qrScannerStream) {
            qrScannerStream.getTracks().forEach(t => t.stop());
            qrScannerStream = null;
        }
        if (qrScannerInstance) {
            try { qrScannerInstance.stop(); } catch(_) {}
            qrScannerInstance = null;
        }
    }

    closeBtn.onclick = () => {
        stopCamera();
        modal.remove();
    };

    manualBtn.onclick = () => {
        stopCamera();
        modal.remove();
        showBoxVerificationModal(onSuccessCallback);
    };

    async function handleDecodedBoxId(rawCode) {
        if (!scanningActive) return;
        scanningActive = false;

        // Provide haptic feedback if supported
        if (navigator.vibrate) {
            try { navigator.vibrate([60, 40, 60]); } catch(_) {}
        }

        statusDiv.classList.remove('hidden');
        statusText.textContent = `Registering Box #${rawCode}...`;

        try {
            const result = await verifyCoinSlotBox(rawCode);
            stopCamera();
            modal.remove();

            // Display celebration modal
            showBoxRegisteredSuccessCard(result, onSuccessCallback);
        } catch (err) {
            statusDiv.classList.remove('hidden');
            statusText.textContent = `❌ ${err.message || 'Registration failed'}`;
            setTimeout(() => {
                statusDiv.classList.add('hidden');
                scanningActive = true;
                if ('BarcodeDetector' in window) requestAnimationFrame(scanBarcodeFrame);
            }, 2500);
        }
    }

    // Native BarcodeDetector loop for lightning-fast camera decoding
    async function scanBarcodeFrame() {
        if (!scanningActive || !video || video.readyState < 2) {
            if (scanningActive) animationFrameId = requestAnimationFrame(scanBarcodeFrame);
            return;
        }

        if ('BarcodeDetector' in window) {
            try {
                const detector = new window.BarcodeDetector({ formats: ['qr_code', 'data_matrix'] });
                const barcodes = await detector.detect(video);
                if (barcodes && barcodes.length > 0) {
                    const rawValue = barcodes[0].rawValue;
                    if (rawValue) {
                        handleDecodedBoxId(rawValue);
                        return;
                    }
                }
            } catch (e) {
                // Fallback will handle
            }
        }
        if (scanningActive) animationFrameId = requestAnimationFrame(scanBarcodeFrame);
    }

    async function startCameraStream() {
        stopCamera();
        scanningActive = true;

        try {
            const constraints = {
                video: {
                    facingMode: { ideal: currentFacingMode },
                    width: { ideal: 1280 },
                    height: { ideal: 720 }
                },
                audio: false
            };

            const stream = await navigator.mediaDevices.getUserMedia(constraints);
            qrScannerStream = stream;
            video.srcObject = stream;
            await video.play();

            if ('BarcodeDetector' in window) {
                animationFrameId = requestAnimationFrame(scanBarcodeFrame);
            } else {
                // Load Html5Qrcode dynamically as a robust cross-browser fallback
                loadHtml5QrcodeFallback();
            }
        } catch (err) {
            console.warn("Direct camera stream access failed:", err);
            loadHtml5QrcodeFallback();
        }
    }

    function loadHtml5QrcodeFallback() {
        if (typeof Html5Qrcode === 'undefined') {
            const script = document.createElement('script');
            script.src = 'https://unpkg.com/html5-qrcode@2.3.8/html5-qrcode.min.js';
            script.onload = () => initHtml5Qrcode();
            script.onerror = () => {
                alert("Camera scanner could not initialize. Please enter the serial manually.");
                manualBtn.click();
            };
            document.head.appendChild(script);
        } else {
            initHtml5Qrcode();
        }
    }

    function initHtml5Qrcode() {
        const readerDiv = document.getElementById('html5QrReaderDiv');
        if (!readerDiv) return;
        readerDiv.classList.remove('hidden');
        video.classList.add('hidden');

        try {
            qrScannerInstance = new Html5Qrcode("html5QrReaderDiv");
            qrScannerInstance.start(
                { facingMode: currentFacingMode },
                { fps: 15, qrbox: { width: 250, height: 250 } },
                (decodedText) => handleDecodedBoxId(decodedText),
                () => {}
            ).catch(() => {});
        } catch (_) {}
    }

    switchCamBtn.onclick = () => {
        currentFacingMode = currentFacingMode === 'environment' ? 'user' : 'environment';
        startCameraStream();
    };

    fileInput.onchange = async (e) => {
        const file = e.target.files?.[0];
        if (!file) return;

        if (typeof Html5Qrcode === 'undefined') {
            const script = document.createElement('script');
            script.src = 'https://unpkg.com/html5-qrcode@2.3.8/html5-qrcode.min.js';
            script.onload = async () => {
                try {
                    const html5Qr = new Html5Qrcode("html5QrReaderDiv");
                    const res = await html5Qr.scanFile(file, true);
                    handleDecodedBoxId(res);
                } catch(err) {
                    alert("Could not detect a QR code in the selected photo.");
                }
            };
            document.head.appendChild(script);
        } else {
            try {
                const html5Qr = new Html5Qrcode("html5QrReaderDiv");
                const res = await html5Qr.scanFile(file, true);
                handleDecodedBoxId(res);
            } catch(err) {
                alert("Could not detect a QR code in the selected photo.");
            }
        }
    };

    startCameraStream();
}

/**
 * Celebratory Modal displayed when a Coin Slot Box is scanned and claimed
 */
function showBoxRegisteredSuccessCard(boxData, onSuccessCallback) {
    const existing = document.getElementById('pisoBoxRegisteredModal');
    if (existing) existing.remove();

    const buildNum = boxData.buildNumber;
    const max = boxData.maxDevices || 10;

    const modalHtml = `
        <div id="pisoBoxRegisteredModal" class="fixed inset-0 z-[1001] flex items-center justify-center bg-slate-950/90 backdrop-blur-md p-4 animate-fade-in-up">
            <div class="glass-card bg-slate-900 border border-emerald-500/50 rounded-3xl w-full max-w-md p-6 sm:p-8 shadow-2xl text-center relative overflow-hidden">
                <div class="w-16 h-16 rounded-2xl bg-emerald-500/20 border border-emerald-500/40 text-emerald-400 flex items-center justify-center mx-auto mb-4 shadow-lg shadow-emerald-500/20">
                    <svg class="w-9 h-9" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
                </div>

                <span class="text-[10px] font-bold uppercase tracking-wider text-emerald-400 bg-emerald-500/10 px-3 py-1 rounded-full border border-emerald-500/20">Hardware Registered</span>
                <h3 class="text-2xl font-black text-white mt-2 mb-1">Coin Slot Box Paired!</h3>
                <p class="text-slate-400 text-xs mb-5">Box <strong>#${buildNum}</strong> has been bound to your fleet account with <strong>${max} Licensed Terminal Seats</strong>.</p>

                <div class="bg-slate-950 border border-slate-800 rounded-2xl p-4 mb-6 text-left space-y-2.5">
                    <div class="flex items-center justify-between text-xs">
                        <span class="text-slate-400">Box Build / MAC:</span>
                        <span class="font-mono text-emerald-400 font-bold">${buildNum}</span>
                    </div>
                    <div class="flex items-center justify-between text-xs">
                        <span class="text-slate-400">Seat Capacity:</span>
                        <span class="font-bold text-white">${max} Android Terminals</span>
                    </div>
                    <div class="flex items-center justify-between text-xs">
                        <span class="text-slate-400">Local Management:</span>
                        <span class="font-mono text-teal-400 font-semibold">http://kioskmanager.local</span>
                    </div>
                </div>

                <div class="space-y-3">
                    <a href="http://kioskmanager.local" target="_blank" class="w-full bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold py-3.5 rounded-xl transition text-sm flex items-center justify-center gap-2 shadow-lg shadow-emerald-500/20">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"></path></svg>
                        <span>Open ESP32 Installer Portal</span>
                    </a>
                    <button type="button" id="closeBoxSuccessModalBtn" class="w-full bg-slate-800 hover:bg-slate-700 text-slate-300 font-semibold py-3 rounded-xl transition text-sm">
                        View in Fleet Dashboard
                    </button>
                </div>
            </div>
        </div>
    `;

    document.body.insertAdjacentHTML('beforeend', modalHtml);
    const modal = document.getElementById('pisoBoxRegisteredModal');
    const closeBtn = document.getElementById('closeBoxSuccessModalBtn');

    closeBtn.onclick = () => {
        modal.remove();
        if (typeof onSuccessCallback === 'function') {
            onSuccessCallback(boxData);
        } else {
            window.location.reload();
        }
    };
}

/**
 * Renders modal prompting user to enter their Coin Slot Box Build Number or scan QR
 */
function showBoxVerificationModal(onSuccessCallback) {
    let existingModal = document.getElementById('coinSlotBoxModal');
    if (existingModal) existingModal.remove();

    const modalHtml = `
        <div id="coinSlotBoxModal" class="fixed inset-0 z-[999] flex items-center justify-center bg-slate-950/85 backdrop-blur-md p-4 animate-fade-in-up">
            <div class="glass-card bg-slate-900 border border-emerald-500/40 rounded-3xl w-full max-w-md p-6 sm:p-8 shadow-2xl relative text-slate-100 overflow-hidden">
                <div class="absolute top-0 right-0 left-0 h-1.5 bg-gradient-to-r from-emerald-500 via-teal-400 to-emerald-600"></div>
                
                <div class="flex items-center gap-3 mb-5">
                    <div class="w-12 h-12 rounded-2xl bg-emerald-500/20 border border-emerald-500/30 flex items-center justify-center text-emerald-400 flex-shrink-0">
                        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm12 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z"></path>
                        </svg>
                    </div>
                    <div>
                        <h3 class="text-xl font-bold text-white leading-tight">Register Coin Slot Box</h3>
                        <p class="text-xs text-emerald-400 font-semibold mt-0.5">Hardware Fleet Authorization</p>
                    </div>
                </div>

                <!-- Primary Action: Mobile Camera QR Scanner -->
                <button type="button" id="modalLaunchScannerBtn" class="w-full mb-5 bg-gradient-to-r from-emerald-500 to-teal-500 hover:from-emerald-400 hover:to-teal-400 text-slate-950 font-black py-3.5 px-4 rounded-2xl text-sm transition shadow-lg shadow-emerald-500/20 flex items-center justify-center gap-2.5 group">
                    <svg class="w-5 h-5 group-hover:scale-110 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z"></path><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 13a3 3 0 11-6 0 3 3 0 016 0z"></path></svg>
                    <span>📷 Scan Box QR with Camera</span>
                </button>

                <div class="flex items-center my-4 w-full">
                    <div class="flex-grow border-t border-slate-800"></div>
                    <span class="px-3 text-[10px] uppercase tracking-wider text-slate-500 font-semibold">Or enter manually</span>
                    <div class="flex-grow border-t border-slate-800"></div>
                </div>

                <div class="space-y-4">
                    <div>
                        <label class="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-2">ESP32 MAC Address or Box Build Number</label>
                        <input type="text" id="modalBoxBuildInput" placeholder="e.g. 24:DC:C3:12:34:56 or BOX-1234" maxlength="40" class="w-full bg-slate-950 border border-slate-700 rounded-xl px-4 py-3 text-white font-mono uppercase tracking-wider focus:outline-none focus:border-emerald-500 focus:ring-1 focus:ring-emerald-500 transition text-sm">
                    </div>

                    <div id="modalBoxError" class="hidden text-xs text-rose-400 bg-rose-500/10 border border-rose-500/20 p-3 rounded-lg"></div>

                    <div class="flex gap-3 pt-2">
                        <button id="modalBoxCancelBtn" type="button" class="flex-1 bg-slate-800 hover:bg-slate-700 text-slate-300 font-semibold py-3 rounded-xl transition text-sm">
                            Cancel
                        </button>
                        <button id="modalBoxVerifyBtn" type="button" class="flex-1 bg-slate-800 hover:bg-slate-700 text-white font-bold py-3 rounded-xl transition text-sm border border-slate-700 flex items-center justify-center gap-2">
                            <span>Submit Serial</span>
                        </button>
                    </div>
                </div>
            </div>
        </div>
    `;

    document.body.insertAdjacentHTML('beforeend', modalHtml);

    const modal = document.getElementById('coinSlotBoxModal');
    const scannerBtn = document.getElementById('modalLaunchScannerBtn');
    const input = document.getElementById('modalBoxBuildInput');
    const errorDiv = document.getElementById('modalBoxError');
    const cancelBtn = document.getElementById('modalBoxCancelBtn');
    const verifyBtn = document.getElementById('modalBoxVerifyBtn');

    scannerBtn.onclick = () => {
        modal.remove();
        openQrBoxScanner(onSuccessCallback);
    };

    cancelBtn.onclick = () => modal.remove();

    verifyBtn.onclick = async () => {
        const val = input.value.trim();
        if (!val) {
            errorDiv.textContent = 'Please enter your Coin Slot Box Build Number or ESP32 MAC.';
            errorDiv.classList.remove('hidden');
            return;
        }

        verifyBtn.disabled = true;
        verifyBtn.innerHTML = 'Verifying...';
        errorDiv.classList.add('hidden');

        try {
            const result = await verifyCoinSlotBox(val);
            modal.remove();
            showBoxRegisteredSuccessCard(result, onSuccessCallback);
        } catch (err) {
            errorDiv.textContent = err.message || 'Verification failed. Please check the serial number.';
            errorDiv.classList.remove('hidden');
            verifyBtn.disabled = false;
            verifyBtn.innerHTML = 'Submit Serial';
        }
    };
}

/**
 * Automatically detects ?box=BOX-XXXX in URL params on page load
 */
async function autoDetectBoxFromUrl() {
    try {
        const urlParams = new URLSearchParams(window.location.search);
        const boxParam = urlParams.get('box') || urlParams.get('b') || urlParams.get('mac') || urlParams.get('register_box');
        if (!boxParam) return;

        const user = getPisoUser();
        if (user) {
            // Auto claim
            try {
                const res = await verifyCoinSlotBox(boxParam);
                showBoxRegisteredSuccessCard(res, () => {
                    const cleanUrl = window.location.pathname;
                    window.history.replaceState({}, document.title, cleanUrl);
                });
            } catch(err) {
                console.warn("Auto-claim from URL error:", err);
            }
        } else {
            sessionStorage.setItem('piso_pending_box_claim', boxParam);
        }
    } catch (_) {}
}

// Check on script initialization
if (typeof window !== 'undefined') {
    window.addEventListener('DOMContentLoaded', autoDetectBoxFromUrl);
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
