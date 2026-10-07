package com.pisophone.kiosk.provisioning

import android.content.Context
import android.content.Intent
import android.os.BaseBundle
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity

/**
 * Setting a phone up by QR code (the main way): on a factory-reset phone, six taps on the welcome screen open Android's QR
 * reader; the code (made by the provisioning page from the coin box's link) tells Android which Wi-Fi to join, where to
 * download this app and the hash of its signing certificate, and carries the box's details in the "admin extras" bundle.
 * Android installs the app, makes it the device owner and hands the bundle to [ProvisioningModeActivity] and
 * [PolicyComplianceActivity] (both only startable by the system), which call [applyFromIntent]. No computer, no USB.
 *
 * The bundle uses the same names as the USB setup's extras: secret, mac, slot, name, wifi_ssid, wifi_pass.
 */
object QrProvisioning {
    private const val TAG = "QrProvisioning"
    const val EXTRA_ADMIN_EXTRAS = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"

    data class Values(
        val secret: String?,
        val mac: String?,
        val slot: Int,
        val name: String?,
        val wifiSsid: String?,
        val wifiPass: String?,
    )

    /** The box's details from the bundle, or null when it carries none (a QR code made by something else). */
    fun fromBundle(b: BaseBundle?): Values? {
        if (b == null) return null
        fun str(key: String) = b.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
        // a number in the QR code's JSON arrives as an int; a quoted one as a string
        val slot = b.getInt("slot", -1).takeIf { it > 0 } ?: str("slot")?.toIntOrNull() ?: -1
        val v = Values(str("secret"), str("mac"), slot, str("name"), str("wifi_ssid"), b.getString("wifi_pass"))
        return v.takeIf { it.secret != null || it.mac != null || it.slot > 0 || !it.wifiPass.isNullOrEmpty() }
    }

    /** The admin extras bundle Android passes to the provisioning screens, if any. */
    fun adminExtras(intent: Intent?): PersistableBundle? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_ADMIN_EXTRAS, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ADMIN_EXTRAS)
        }
    }

    /** Stores the box's details like the USB setup does and marks the phone as set up. Safe to call twice. */
    fun apply(
        context: Context,
        v: Values,
    ): Boolean {
        KioskSecurity.applyDirectProvisioning(
            context = context,
            secret = v.secret,
            mac = v.mac,
            slot = v.slot,
            name = v.name,
            wifiSsid = v.wifiSsid,
            wifiPassword = v.wifiPass,
        )
        KioskActivationManager.recordDeviceIdentity(context)
        KioskActivationManager.setPairingCompleted(context, true)
        // the home screen then asks the installer to turn on "display over other apps" (no PIN for 30 minutes)
        OverlayPermissionStep.markQrSetup(context)
        Log.i(TAG, "Set up from the QR code: MAC=${v.mac}, slot=${v.slot}, secret=${v.secret != null}, Wi-Fi=${!v.wifiPass.isNullOrEmpty()}")
        return true
    }

    fun applyFromIntent(
        context: Context,
        intent: Intent?,
    ): Boolean {
        val v = fromBundle(adminExtras(intent))
        if (v == null) {
            Log.w(TAG, "The QR code carried no coin box details: the phone is set up, but must be paired from the box's page")
            return false
        }
        return apply(context, v)
    }
}
