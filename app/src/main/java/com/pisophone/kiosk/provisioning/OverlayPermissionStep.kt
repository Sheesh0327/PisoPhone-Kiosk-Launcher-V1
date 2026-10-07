package com.pisophone.kiosk.provisioning

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver
import com.pisophone.kiosk.security.AdminMaintenanceMode
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity

/**
 * The last setup step on a phone set up by QR code: "Display over other apps".
 *
 * The lock screen and the time bubble are overlay windows, which need that permission. The USB setup grants it over ADB; a
 * QR setup cannot (no app may grant it to itself, not even the device owner), so the home screen shows this step instead of
 * the apps until it is on. Which way comes first depends on the phone ([usbFirst]): normally one button opens the setting
 * and the installer turns PisoPhone on; on an Android Go phone (no such switch) the setup computer grants it over USB
 * (USB debugging was turned on by the app at the end of the QR setup, see KioskPolicyManager.enableAdbForSetup). While the setting
 * is open, Settings is let through the kiosk lock for a few minutes (an admin maintenance window) and the
 * "modify apps" restriction is lifted; both are restored as soon as the permission is on (or the window runs out).
 * Within the first 30 minutes after the QR setup (or while the phone has no admin PIN yet) no PIN is asked; after that
 * the admin PIN is, so a customer can never use this to reach Settings.
 */
object OverlayPermissionStep {
    private const val TAG = "OverlayPermissionStep"
    private const val SETTINGS_WINDOW_SECONDS = 300
    private const val NO_PIN_AFTER_SETUP_MS = 30 * 60 * 1000L
    private const val PREFS = "kiosk_overlay_step"
    private const val KEY_QR_SETUP_AT = "qr_setup_at_ms"

    @Volatile
    private var settingsOpened = false

    fun isNeeded(context: Context): Boolean = !Settings.canDrawOverlays(context)

    /**
     * Android Go phones (low-RAM) do not offer the "display over other apps" switch: there the USB way comes first (the
     * setup computer grants it over ADB). Other phones turn the switch on, with USB as the fallback.
     */
    fun usbFirst(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    /** The QR setup just finished: the step needs no PIN for the next 30 minutes. */
    fun markQrSetup(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_QR_SETUP_AT, System.currentTimeMillis()).apply()
    }

    private fun justSetUp(context: Context): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_QR_SETUP_AT, 0L)
        val age = System.currentTimeMillis() - at
        return at > 0L && age in 0 until NO_PIN_AFTER_SETUP_MS
    }

    /** No PIN right after the setup, or before the phone has learned its admin PIN from the box. */
    fun needsPin(context: Context): Boolean =
        !justSetUp(context) && !KioskActivationManager.isSetupModeActive(context) && !KioskSecurity.isAdminPinUnset(context)

    private fun admin(context: Context): Pair<DevicePolicyManager, ComponentName>? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return null
        if (!dpm.isDeviceOwnerApp(context.packageName)) return null
        return dpm to ComponentName(context, KioskDeviceAdminReceiver::class.java)
    }

    /** Opens the "Display over other apps" setting (PisoPhone's own page where Android allows it, else the list). */
    fun openSetting(activity: Activity): Boolean {
        admin(activity)?.let { (dpm, component) ->
            AdminMaintenanceMode.begin(activity, SETTINGS_WINDOW_SECONDS) // Settings may run inside the kiosk lock
            try {
                dpm.clearUserRestriction(component, UserManager.DISALLOW_APPS_CONTROL)
            } catch (e: Exception) {
                Log.w(TAG, "Could not lift DISALLOW_APPS_CONTROL: ${e.message}")
            }
        }
        settingsOpened = true
        val own = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
        return try {
            activity.startActivity(own)
            true
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                true
            } catch (e2: Exception) {
                Log.e(TAG, "No \"display over other apps\" setting on this phone: ${e2.message}")
                false
            }
        }
    }

    /** Back from Settings with the permission on: Settings is locked away again. */
    fun finishIfGranted(context: Context) {
        if (!settingsOpened || isNeeded(context)) return
        settingsOpened = false
        admin(context)?.let { (dpm, component) ->
            try {
                dpm.addUserRestriction(component, UserManager.DISALLOW_APPS_CONTROL)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore DISALLOW_APPS_CONTROL: ${e.message}")
            }
        }
        AdminMaintenanceMode.end(context)
        // USB debugging was on for the USB fallback (QR setup); with the switch on it is not needed any more
        val app = context.applicationContext ?: context
        Thread { KioskSecurity.setAdbAllowed(app, false) }.start()
        Log.i(TAG, "\"Display over other apps\" is on: the kiosk lock screen can show; USB debugging goes off.")
    }
}

/**
 * The step's screen, shown by the home screen instead of the apps until the permission is on. [usbFirst] (an Android Go
 * phone, which does not offer the switch) puts the USB way first; elsewhere the switch is the way, USB the fallback.
 */
@Composable
fun OverlayPermissionScreen(
    usbFirst: Boolean,
    needsPin: Boolean,
    onOpenSetting: (pin: String) -> String?,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val usbText = "Keep this phone plugged into the setup computer by USB, tap Allow when it asks \"Allow USB debugging?\", " +
        "and click Finish over USB on the setup page. Everything else happens by itself."
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("One last step", fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "PisoPhone needs \"Display over other apps\" to show its lock screen and the time bubble over other apps.",
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (usbFirst) {
            Text("This phone sets it over USB", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(usbText, fontSize = 15.sp)
        } else {
            Text("1. Tap Open the setting below.", fontSize = 15.sp)
            Text("2. Choose PisoPhone and turn on \"Allow display over other apps\".", fontSize = 15.sp)
            Text("3. Press Back to return here. The kiosk starts by itself.", fontSize = 15.sp)
        }
        if (needsPin) {
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 32) pin = it },
                label = { Text("Admin PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(24.dp))
        if (usbFirst) {
            OutlinedButton(onClick = { error = onOpenSetting(pin) }, modifier = Modifier.fillMaxWidth()) {
                Text("Try the setting on the phone instead")
            }
        } else {
            Button(
                onClick = { error = onOpenSetting(pin) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Open the setting", fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Switch missing or greyed out? $usbText", fontSize = 13.sp)
        }
    }
}
