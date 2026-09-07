/**
 * Mobile-First Helper for PisoPhone Cloud Management Dashboard
 * Welcomes mobile and tablet operators to scan Coin Slot Box QR codes
 * and manage hardware fleets effortlessly.
 */
(function() {
    // Mobile-first touch enhancements
    function initMobileOptimizations() {
        if (document.documentElement) {
            document.documentElement.classList.add('mobile-friendly');
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initMobileOptimizations);
    } else {
        initMobileOptimizations();
    }
})();
