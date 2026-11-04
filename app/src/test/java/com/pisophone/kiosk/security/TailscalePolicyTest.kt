package com.pisophone.kiosk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscalePolicyTest {

    @Test
    fun tailscalePackageConstantMatchesOfficialApp() {
        assertEquals("com.tailscale.ipn", TailscalePolicy.TAILSCALE_PACKAGE)
    }

    @Test
    fun identifiesTailscaleCgnatRange100Dot64() {
        // 100.64.0.0 to 100.127.255.255
        assertTrue(TailscalePolicy.isTailscaleIp("100.64.0.1"))
        assertTrue(TailscalePolicy.isTailscaleIp("100.115.92.14"))
        assertTrue(TailscalePolicy.isTailscaleIp("100.127.255.254"))
        assertTrue(TailscalePolicy.isTailscaleIp("100.80.1.5"))

        // Boundary violations
        assertFalse(TailscalePolicy.isTailscaleIp("100.63.255.255"))
        assertFalse(TailscalePolicy.isTailscaleIp("100.128.0.0"))
        assertFalse(TailscalePolicy.isTailscaleIp("100.128.0.1"))

        // Standard private/LAN IPs
        assertFalse(TailscalePolicy.isTailscaleIp("192.168.1.1"))
        assertFalse(TailscalePolicy.isTailscaleIp("10.0.0.1"))
        assertFalse(TailscalePolicy.isTailscaleIp("172.16.0.1"))

        // Invalid
        assertFalse(TailscalePolicy.isTailscaleIp(""))
        assertFalse(TailscalePolicy.isTailscaleIp("unknown"))
        assertFalse(TailscalePolicy.isTailscaleIp("100.64.0"))
    }

    @Test
    fun remoteParentDashboardAccessPolicy() {
        // In Home mode: Tailscale IP or Localhost are permitted
        assertTrue(TailscalePolicy.isParentDashboardAllowed("100.115.92.14", isHomeMode = true))
        assertTrue(TailscalePolicy.isParentDashboardAllowed("127.0.0.1", isHomeMode = true))
        assertTrue(TailscalePolicy.isParentDashboardAllowed("::1", isHomeMode = true))

        // In Home mode: Public WAN IP is blocked
        assertFalse(TailscalePolicy.isParentDashboardAllowed("8.8.8.8", isHomeMode = true))
        assertFalse(TailscalePolicy.isParentDashboardAllowed("192.168.1.50", isHomeMode = true))

        // Outside Home mode (Commercial PisoPhone): Remote parent console is disabled
        assertFalse(TailscalePolicy.isParentDashboardAllowed("100.115.92.14", isHomeMode = false))
    }
}
