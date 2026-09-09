package com.pisophone.kiosk.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Resolves the primary local IPv4 address of the device (prioritizing wlan / eth interfaces).
     * Returns "127.0.0.1" if no active network interface is available.
     */
    fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            var fallbackIp: String? = null
            for (intf in interfaces.asSequence()) {
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs.asSequence()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            return host
                        }
                        if (fallbackIp == null) {
                            fallbackIp = host
                        }
                    }
                }
            }
            fallbackIp ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
