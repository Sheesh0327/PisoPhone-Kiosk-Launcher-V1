/**
 * Device Compatibility & Mobile Detector for PisoPhone WebUSB Platform
 * Informs users on mobile phones/tablets that WebUSB hardware installation & ADB
 * requires a desktop/laptop computer with Google Chrome, Edge, or Brave.
 */
(function() {
    function isMobileOrTabletDevice() {
        const ua = navigator.userAgent || navigator.vendor || window.opera;
        const mobileUaMatch = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile|Silk/i.test(ua);
        const smallTouchScreen = (window.innerWidth < 850 && ('ontouchstart' in window || navigator.maxTouchPoints > 0));
        return mobileUaMatch || smallTouchScreen;
    }

    function initMobileWarning() {
        if (!isMobileOrTabletDevice()) return;

        const isDismissed = sessionStorage.getItem('pisophone_mobile_warning_dismissed') === '1';

        // 1. Inject Styles
        const style = document.createElement('style');
        style.innerHTML = `
            #pisoMobileBanner {
                position: sticky;
                top: 0;
                z-index: 99999;
                background: linear-gradient(90deg, #991b1b, #7f1d1d);
                border-bottom: 1px solid #ef4444;
                color: #fef2f2;
                padding: 10px 16px;
                font-size: 13px;
                display: flex;
                align-items: center;
                justify-content: space-between;
                box-shadow: 0 4px 12px rgba(0,0,0,0.5);
                font-family: system-ui, -apple-system, sans-serif;
            }
            #pisoMobileBanner a {
                color: #fca5a5;
                text-decoration: underline;
                font-weight: 600;
            }
            #pisoMobileModalOverlay {
                position: fixed;
                inset: 0;
                background: rgba(2, 6, 23, 0.88);
                backdrop-filter: blur(12px);
                -webkit-backdrop-filter: blur(12px);
                z-index: 100000;
                display: flex;
                align-items: center;
                justify-content: center;
                padding: 16px;
                font-family: system-ui, -apple-system, sans-serif;
            }
            .piso-mobile-modal {
                background: #0f172a;
                border: 1px solid rgba(239, 68, 68, 0.4);
                border-radius: 20px;
                max-width: 520px;
                width: 100%;
                padding: 24px;
                color: #f8fafc;
                box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.8), 0 0 30px rgba(239, 68, 68, 0.15);
                animation: pisoSlideUp 0.3s cubic-bezier(0.16, 1, 0.3, 1);
            }
            @keyframes pisoSlideUp {
                from { opacity: 0; transform: translateY(20px) scale(0.96); }
                to { opacity: 1; transform: translateY(0) scale(1); }
            }
        `;
        document.head.appendChild(style);

        // 2. Add Top Warning Banner
        const banner = document.createElement('div');
        banner.id = 'pisoMobileBanner';
        banner.innerHTML = `
            <div style="display:flex; align-items:center; gap:8px; flex:1;">
                <span style="font-size:16px;">⚠️</span>
                <span><strong>Desktop PC Required:</strong> WebUSB device flashing does not work on mobile phones. Open this page on a PC.</span>
            </div>
            <button id="pisoShowModalBtn" style="background:#450a0a; border:1px solid #ef4444; color:#fff; font-size:11px; font-weight:700; padding:4px 10px; border-radius:6px; margin-left:10px; white-space:nowrap; cursor:pointer;">Learn More</button>
        `;
        document.body.prepend(banner);

        // 3. Add Modal Overlay if not previously dismissed in this session
        const modalOverlay = document.createElement('div');
        modalOverlay.id = 'pisoMobileModalOverlay';
        modalOverlay.style.display = isDismissed ? 'none' : 'flex';

        modalOverlay.innerHTML = `
            <div class="piso-mobile-modal">
                <div style="display:flex; align-items:flex-start; gap:16px; margin-bottom:16px;">
                    <div style="width:48px; height:48px; border-radius:12px; background:rgba(239, 68, 68, 0.15); border:1px solid rgba(239, 68, 68, 0.4); display:flex; align-items:center; justify-content:center; flex-shrink:0;">
                        <svg style="width:26px; height:26px; color:#ef4444;" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"></path>
                        </svg>
                    </div>
                    <div>
                        <h2 style="margin:0 0 4px 0; font-size:18px; font-weight:800; color:#ffffff;">Desktop PC Browser Required</h2>
                        <span style="display:inline-block; background:rgba(239, 68, 68, 0.2); color:#fca5a5; font-size:11px; font-weight:700; padding:2px 8px; border-radius:4px; border:1px solid rgba(239, 68, 68, 0.3);">
                            Mobile Device Detected
                        </span>
                    </div>
                </div>

                <p style="margin:0 0 16px 0; font-size:13px; color:#cbd5e1; line-height:1.5;">
                    You are viewing this site from a <strong>smartphone or tablet</strong>. Mobile operating systems (iOS &amp; Android) <strong>do not allow WebUSB hardware communication</strong>, meaning you cannot flash, debloat, sideload, or activate PisoPhone directly from this phone.
                </p>

                <div style="background:#090e1a; border:1px solid #1e293b; border-radius:12px; padding:14px; margin-bottom:18px;">
                    <div style="font-size:11px; font-weight:800; color:#94a3b8; text-transform:uppercase; letter-spacing:0.5px; margin-bottom:8px;">
                        How to use PisoPhone tools:
                    </div>
                    <ul style="margin:0; padding-left:18px; font-size:12px; color:#e2e8f0; line-height:1.6;">
                        <li>Open this portal on a <strong>Windows PC, Mac, or Linux</strong> computer.</li>
                        <li>Use <strong>Google Chrome</strong>, <strong>Microsoft Edge</strong>, or <strong>Brave</strong>.</li>
                        <li>Connect your PisoPhone kiosk device to the PC using a USB data cable.</li>
                    </ul>
                </div>

                <div style="display:flex; flex-direction:column; gap:10px;">
                    <button id="pisoCopyLinkBtn" style="width:100%; background:#10b981; hover:background:#059669; color:#022c22; font-weight:800; font-size:13px; padding:12px; border-radius:10px; border:none; cursor:pointer; display:flex; align-items:center; justify-content:center; gap:8px; transition:all 0.2s;">
                        <svg style="width:16px; height:16px;" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 5H6a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2v-1M8 5a2 2 0 002 2h2a2 2 0 002-2M8 5a2 2 0 012-2h2a2 2 0 012 2m0 0h2a2 2 0 012 2v3m2 4H10m0 0l3-3m-3 3l3 3"></path></svg>
                        <span id="pisoCopyText">Copy Portal Link to Open on PC</span>
                    </button>
                    <button id="pisoDismissBtn" style="width:100%; background:#1e293b; color:#94a3b8; font-weight:600; font-size:12px; padding:10px; border-radius:10px; border:1px solid #334155; cursor:pointer; transition:all 0.2s;">
                        I Understand (Browse Info Only)
                    </button>
                </div>
            </div>
        `;

        document.body.appendChild(modalOverlay);

        // Bind Actions
        const copyBtn = document.getElementById('pisoCopyLinkBtn');
        const copyText = document.getElementById('pisoCopyText');
        const dismissBtn = document.getElementById('pisoDismissBtn');
        const showModalBtn = document.getElementById('pisoShowModalBtn');

        copyBtn.addEventListener('click', async () => {
            try {
                await navigator.clipboard.writeText(window.location.href);
                copyText.textContent = '✅ Link Copied! Paste on your PC';
                copyBtn.style.background = '#34d399';
                setTimeout(() => {
                    copyText.textContent = 'Copy Portal Link to Open on PC';
                    copyBtn.style.background = '#10b981';
                }, 3000);
            } catch (e) {
                prompt("Copy this URL to open on your PC:", window.location.href);
            }
        });

        dismissBtn.addEventListener('click', () => {
            modalOverlay.style.display = 'none';
            sessionStorage.setItem('pisophone_mobile_warning_dismissed', '1');
        });

        showModalBtn.addEventListener('click', () => {
            modalOverlay.style.display = 'flex';
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initMobileWarning);
    } else {
        initMobileWarning();
    }
})();
