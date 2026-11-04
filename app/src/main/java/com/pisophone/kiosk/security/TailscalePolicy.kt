package com.pisophone.kiosk.security

/**
 * Pure policy rules for Tailscale remote parental management.
 * Checks Tailscale CGNAT subnet (100.64.0.0/10) and controls remote access to the phone's parent console.
 */
object TailscalePolicy {
    /** Package name of the official Tailscale Android client. */
    const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

    /**
     * Determines if [ip] is within Tailscale's CGNAT IPv4 address space:
     * 100.64.0.0 to 100.127.255.255 (100.64.0.0/10).
     */
    fun isTailscaleIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false

        val octet0 = parts[0].toIntOrNull() ?: return false
        val octet1 = parts[1].toIntOrNull() ?: return false
        val octet2 = parts[2].toIntOrNull() ?: return false
        val octet3 = parts[3].toIntOrNull() ?: return false

        if (octet0 != 100) return false
        if (octet1 !in 64..127) return false
        if (octet2 !in 0..255) return false
        if (octet3 !in 0..255) return false

        return true
    }

    /**
     * Authorizes parent dashboard routes on the kid's phone.
     * Accessible over Tailscale (100.64.0.0/10) or localhost (loopback for local maintenance/tests)
     * only when Home Mode is active.
     */
    fun isParentDashboardAllowed(remoteIp: String?, isHomeMode: Boolean): Boolean {
        if (!isHomeMode || remoteIp.isNullOrBlank()) return false
        val cleanIp = remoteIp.trim()
        if (cleanIp == "127.0.0.1" || cleanIp == "::1" || cleanIp == "localhost") return true
        return isTailscaleIp(cleanIp)
    }
}
