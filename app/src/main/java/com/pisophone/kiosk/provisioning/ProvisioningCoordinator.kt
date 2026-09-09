package com.pisophone.kiosk.provisioning

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.KioskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ProvisioningCoordinator {
    const val PREFS_NAME = "provisioning_state"
    const val KEY_PAIRING_PENDING = "pairing_pending"
    const val KEY_SLOT = "setup_slot"
    const val KEY_MAC = "setup_mac"
    const val KEY_IP = "setup_ip"
    const val KEY_NAME = "setup_name"

    fun extractAndSaveAdminExtras(context: Context, intent: Intent) {
        val extras: PersistableBundle? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }

        if (extras != null) {
            val schemaVersion = extras.getInt("setup_schema_version", -1)
            Log.i("Provisioning", "Found admin extras, schema: $schemaVersion")
            
            if (schemaVersion == 1) {
                val secret = extras.getString("setup_secret")
                val mac = extras.getString("setup_mac")
                val ip = extras.getString("setup_ip")
                val slot = extras.getInt("setup_slot", -1)
                val name = extras.getString("setup_name")

                if (secret != null && mac != null && slot > 0) {
                    KioskSecurity.applyDirectProvisioning(
                        context = context,
                        secret = secret,
                        mac = mac,
                        ip = ip,
                        slot = slot,
                        name = name
                    )
                    
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean(KEY_PAIRING_PENDING, true)
                        .putInt(KEY_SLOT, slot)
                        .putString(KEY_MAC, mac)
                        .putString(KEY_IP, ip)
                        .putString(KEY_NAME, name)
                        .apply()
                        
                    Log.i("Provisioning", "Saved provisioning configuration for slot $slot. Pairing pending.")
                } else {
                    Log.e("Provisioning", "Missing required fields in admin extras.")
                }
            } else {
                Log.e("Provisioning", "Unsupported schema version: $schemaVersion")
            }
        }
    }

    fun isPairingPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PAIRING_PENDING, false)
    }
    
    fun markPairingComplete(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PAIRING_PENDING, false).apply()
    }
    
    fun initiatePairingAsync(context: Context, onComplete: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val success = attemptPairing(context)
            withContext(Dispatchers.Main) {
                if (success) {
                    markPairingComplete(context)
                }
                onComplete(success)
            }
        }
    }

    private fun attemptPairing(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val slot = prefs.getInt(KEY_SLOT, -1)
        var ip = prefs.getString(KEY_IP, null)
        
        if (slot < 1) return false
        
        // Wait, IP might have changed. But for now we try the stored IP.
        // We will need to sign the request.
        val secret = KioskSecurity.getSharedSecret(context)
        if (secret.isNullOrEmpty()) return false
        
        val deviceId = com.pisophone.kiosk.security.HardwareLockManager.getHardwareFingerprint(context)
        val ts = System.currentTimeMillis()
        
        val challenge = "$deviceId:$ts"
        val sig = KioskSecurity.calculateHmac(challenge, secret)
        
        if (ip.isNullOrBlank()) {
             // We can't pair if we don't have the IP. In a real setup, discovery would find it.
             return false
        }
        
        try {
            val url = URL("http://$ip/api/slots/pair")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            
            // Build post body
            val postData = "device_id=$deviceId&ts=$ts&sig=$sig&slot=$slot&id=$deviceId&ip=&name=" // ESP32 will pick up IP automatically
            conn.outputStream.write(postData.toByteArray(Charsets.UTF_8))
            
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val input = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(input)
                if (json.optBoolean("success")) {
                    Log.i("Provisioning", "Successfully paired with ESP32 at $ip")
                    return true
                }
            }
            Log.e("Provisioning", "Pairing failed, HTTP $responseCode")
        } catch (e: Exception) {
            Log.e("Provisioning", "Pairing exception: ${e.message}")
        }
        return false
    }
}
