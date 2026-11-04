package com.pisophone.kiosk.server

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.network.BoxClock
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.security.TailscalePolicy
import com.pisophone.kiosk.util.ChoreItem
import com.pisophone.kiosk.util.ChoreStatus
import com.pisophone.kiosk.util.CurfewSchedule
import com.pisophone.kiosk.util.KioskSettings
import com.pisophone.kiosk.util.PiggyBankGoal
import com.pisophone.kiosk.voice.VoiceMessageReceiver
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

interface KioskServerDelegate {
    fun isReady(): Boolean = true
    fun getSecretKey(): String
    fun getDeviceId(): String = ""
    fun onHeartbeat(clientIp: String?)
    fun getStatusJson(): JSONObject
    fun getSessionTimeRemaining(): Int
    fun getAppState(): Int
    fun getAuditEventsJson(): String
    fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult
    fun onDeductTime(seconds: Int, txId: String? = null)
    fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int? = null)

    /**
     * The shop's branding the box pushed with the config (feature 013). A value is `null` when the box did not send it; the
     * default does nothing, so a delegate written before this feature keeps compiling and an old box changes nothing.
     */
    fun onBrandingUpdated(palette: String?, shopName: String?, title: String?, density: String?, deviceLabel: String? = null) {}

    /**
     * A voice message from the box (feature 056) that has arrived whole and checked out, as 8 kHz mono samples. The default does nothing,
     * so a delegate written before this feature keeps compiling.
     */
    fun onVoiceMessage(samples: ShortArray) {}

    fun onTriggerAction(action: String, slotNum: Int? = null, extra: Map<String, String>? = null)
    fun getCrashLog(): String?
    fun onPauseSession() {}
    fun onResumeSession() {}
}

/**
 * HTTP server the ESP32 calls on the phone (add time, deduct time, config, a few harmless actions).
 * Apart from /ping and /challenge (fixed "OK"), every request carries an encrypted payload and an HMAC signature
 * made with the box secret; destructive actions are not reachable here at all;
 * requests are rate limited per client IP. Business logic is delegated through the delegate
 * interface above, this class only authenticates and routes.
 */
class KioskHttpServer(
    private val context: Context,
    private val port: Int,
    private val delegate: KioskServerDelegate,
    private val freshness: RequestFreshness = RequestFreshness(BoxClock::nowMs, BoxClock::isSynced),
) : NanoHTTPD(port) {
    companion object {
        private const val TAG = "KioskHttpServer"
        private const val RATE_LIMIT_WINDOW_MS = 60000L
        private const val MAX_REQUESTS_PER_WINDOW = 60
        private const val MAX_TRACKED_IPS = 256

        /** The params of the box's config push before feature 013. */
        private val CONFIG_PARAMS = listOf("price", "minutes", "device_name", "admin_pin", "slot", "slot_num")

        /** The optional branding params of the box's /config push (feature 013). */
        internal val BRANDING_PARAMS = listOf("palette", "shop_name", "brand_title", "anim_density", "device_label")

        /** A branding value is at most this long on the wire (the longest allowed name is 32 characters, up to 12 bytes encoded each). */
        private const val MAX_BRANDING_WIRE_CHARS = 512

        /**
         * One branding value as the box sent it: percent-encoded text (a shop name may hold spaces or an ampersand), decoded here
         * and only here, so the other /config params parse exactly as before. `null` when the box did not send it, when it is
         * too long to be real, or when it is not valid encoding; the caller then leaves that setting alone.
         */
        internal fun decodeBrandingValue(raw: String?): String? {
            if (raw == null || raw.length > MAX_BRANDING_WIRE_CHARS) return null
            return try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Branding value the box sent is not valid encoding, leaving that setting alone", e)
                null
            }
        }

        /** Actions the ESP32 box sends to a phone. Anything else arriving over the network is refused. */
        internal val NETWORK_ACTIONS = setOf(
            "arena_mode_activate_p1",
            "arena_mode_activate_p2",
            "arena_mode_activate",
            "arena_mode_deactivate",
            "arena_mode_end",
            "slot_lockdown",
            "slot_restore",
            "slot_renew",
            "reset_time",
            "identify",
            "vibrate",
            "sound",
            "flash",
            "locate",
            "locate_stop",
        )
    }

    private val rateLimits = ConcurrentHashMap<String, MutableList<Long>>()
    private val replayGuard = ReplayGuard()

    private fun parseQueryString(queryString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (queryString.isBlank()) return result
        val pairs = queryString.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun isRateLimited(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "::1" || ip == "localhost") {
            return false
        }
        val now = System.currentTimeMillis()
        if (rateLimits.size > MAX_TRACKED_IPS) {
            // Drop IPs whose last request is outside the window so the map cannot grow without bound.
            rateLimits.entries.removeIf { (_, list) ->
                synchronized(list) { list.isEmpty() || now - list.last() > RATE_LIMIT_WINDOW_MS }
            }
        }
        val timestamps = rateLimits.getOrPut(ip) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeAll { now - it > RATE_LIMIT_WINDOW_MS }
            if (timestamps.size >= MAX_REQUESTS_PER_WINDOW) {
                return true
            }
            timestamps.add(now)
            return false
        }
    }

    private fun createResponse(status: Response.IStatus, mimeType: String, txt: String): Response {
        val bytes = txt.toByteArray(Charsets.UTF_8)
        val response = newFixedLengthResponse(status, mimeType, java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        response.setGzipEncoding(false)
        response.addHeader("Connection", "close")
        return response
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters.mapValues { it.value.firstOrNull() ?: "" }

        // The only unauthenticated routes: liveness probes that return a fixed answer and touch nothing.
        // Everything that reads state, changes state or reports a heartbeat needs the signed envelope below.
        if (uri == "/ping" || uri == "/challenge" || uri == "/heartbeat_challenge") {
            return createResponse(Response.Status.OK, "text/plain", "OK")
        }

        val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"] ?: "unknown"
        if (isRateLimited(clientIp)) {
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "Rate limit exceeded")
        }

        if (uri.startsWith("/parent")) {
            return serveParentDashboard(session, uri, params, clientIp)
        }

        val secretKey = delegate.getSecretKey()
        val payload = params["payload"]
        if (payload.isNullOrBlank()) {
            Log.w(TAG, "Rejected unauthenticated request to protected endpoint: $uri")
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "Encrypted payload required")
        }

        val hmac = params["hmac"]
        if (hmac.isNullOrBlank()) {
            Log.w(TAG, "Rejected request without HMAC signature: $uri")
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "HMAC signature required for integrity")
        }

        val expectedHmac = KioskSecurity.calculateHmac(payload, secretKey)
        if (!KioskSecurity.constantTimeEquals(hmac.trim().lowercase(), expectedHmac.trim().lowercase())) {
            Log.w(TAG, "Rejected payload with invalid HMAC signature: $uri")
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "HMAC verification failed")
        }

        val decryptedStr = KioskSecurity.decrypt(payload, secretKey)
        if (decryptedStr.isBlank()) {
            Log.w(TAG, "Rejected payload with invalid AES key or corrupted signature: $uri")
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "Decryption failed")
        }
        val decryptedParams = parseQueryString(decryptedStr)

        // Replay protection layer 1: freshness. A payment is idempotent by tx_id (the box repeats it, with its original
        // timestamp, until acknowledged), so it is not judged by time. Anything else must carry a time close to the box's clock,
        // so a command captured on the network cannot be played back later.
        val ts = decryptedParams["ts"]?.trim()?.toLongOrNull() ?: 0L
        val isPayment = uri == "/add_time" || uri == "/coin"
        if (!isPayment) {
            when (freshness.check(ts)) {
                RequestFreshness.Verdict.FRESH -> {}
                RequestFreshness.Verdict.UNVERIFIED ->
                    Log.w(TAG, "Notice on $uri: no box clock to check ts=$ts against yet; relying on the signature cache only")
                RequestFreshness.Verdict.OUTSIDE_WINDOW -> {
                    Log.w(TAG, "Rejected stale signed request to $uri (ts=$ts, box clock=${BoxClock.nowMs()})")
                    return createResponse(Response.Status.FORBIDDEN, "text/plain", "STALE_TIMESTAMP")
                }
            }
        }

        // Replay Protection check: verify unique tx_id (Layer 2)
        val txId = (decryptedParams["tx_id"] ?: decryptedParams["nonce"])?.trim() ?: ""
        if (uri == "/add_time" || uri == "/coin") {
            if (!delegate.isReady()) {
                Log.w(TAG, "Rejecting payment request to $uri: Server initialization in progress")
                return createResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "INITIALIZING")
            }
            if (txId.isBlank()) {
                Log.w(TAG, "Rejecting coin credit: Missing tx_id in payload")
                return createResponse(Response.Status.BAD_REQUEST, "text/plain", "MISSING_TX_ID")
            }
        }

        // A signed message is accepted once. Payments are exempt: the box repeats them until acknowledged and
        // they are already idempotent by tx_id. A repeat of anything else is acknowledged but not executed again.
        if (!isPayment && !replayGuard.firstSeen(hmac.trim().lowercase())) {
            Log.w(TAG, "Ignoring repeated signed request to $uri")
            return createResponse(Response.Status.OK, "text/plain", "OK:DUPLICATE")
        }

        return when (uri) {
            "/heartbeat" -> {
                val remoteIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
                delegate.onHeartbeat(remoteIp)
                createResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/identify", "/status" -> {
                val remoteIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
                delegate.onHeartbeat(remoteIp)
                createResponse(Response.Status.OK, "application/json", delegate.getStatusJson().toString())
            }
            "/get_time" -> createResponse(Response.Status.OK, "text/plain", delegate.getSessionTimeRemaining().toString())
            "/state" -> createResponse(Response.Status.OK, "text/plain", delegate.getAppState().toString())
            "/audit" -> createResponse(Response.Status.OK, "application/json", delegate.getAuditEventsJson())
            "/add_time", "/coin" -> {
                val targetDev = decryptedParams["device_id"]?.trim() ?: ""
                val myDeviceId = delegate.getDeviceId().ifBlank { KioskSecurity.getHardwareId(context) }
                if (targetDev.isNotBlank() && !targetDev.equals(myDeviceId, ignoreCase = true)) {
                    Log.w(TAG, "Rejecting payment request: Recipient mismatch (target='$targetDev', local='$myDeviceId')")
                    return createResponse(Response.Status.FORBIDDEN, "text/plain", "MISMATCHED_RECIPIENT")
                }

                val hasMinutes = decryptedParams.containsKey("minutes")
                val hasSeconds = decryptedParams.containsKey("seconds")
                val minutesLong = decryptedParams["minutes"]?.toLongOrNull()
                val secondsParamLong = decryptedParams["seconds"]?.toLongOrNull()
                if ((hasMinutes && minutesLong == null) || (hasSeconds && secondsParamLong == null) || (!hasMinutes && !hasSeconds)) {
                    Log.w(TAG, "Rejecting coin credit: Invalid time parameters")
                    return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_PAYMENT")
                }
                val rawSecondsLong = secondsParamLong ?: ((minutesLong ?: 0L) * 60L)

                val hasAmount = decryptedParams.containsKey("amount")
                val amountParam = decryptedParams["amount"]?.toDoubleOrNull()
                if (hasAmount && (amountParam == null || amountParam.isNaN() || amountParam.isInfinite())) {
                    Log.w(TAG, "Rejecting coin credit: Invalid amount parameter")
                    return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_AMOUNT")
                }
                // No amount means complimentary time (APP-06), not a 1 peso coin; the signed v_sig path uses the same 0.
                val amount = amountParam ?: 0.0
                if (amount < 0.0) {
                    Log.w(TAG, "Rejecting coin credit: Negative amount")
                    return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_AMOUNT")
                }

                val amountPulses = amount.toInt()
                val tsParam = decryptedParams["ts"]?.trim() ?: ""
                val vSig = decryptedParams["v_sig"]?.trim() ?: ""
                if (vSig.isNotBlank()) {
                    val expectedVSig = KioskSecurity.calculateHmac("v1:$targetDev:$txId:$amountPulses:$tsParam", secretKey)
                    if (!KioskSecurity.constantTimeEquals(vSig.lowercase(), expectedVSig.lowercase())) {
                        Log.w(TAG, "Rejecting payment: Invalid versioned HMAC signature for $txId")
                        return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "INVALID_SIGNATURE")
                    }
                }

                if (rawSecondsLong > 0) {
                    if (rawSecondsLong > Int.MAX_VALUE.toLong()) {
                        Log.w(TAG, "Rejecting coin credit: seconds parameter exceeds Int.MAX_VALUE ($rawSecondsLong)")
                        return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
                    }
                    val seconds = rawSecondsLong.toInt()
                    val result = try {
                        delegate.creditPayment(txId, seconds, amount)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during creditPayment for $txId: ${e.message}", e)
                        PaymentResult.FAILED
                    }

                    val ackResp = if (targetDev.isNotBlank()) {
                        val ackNow = System.currentTimeMillis()
                        val ackPayload = "v1:$targetDev:$txId:$amountPulses:$ackNow"
                        val ackSig = KioskSecurity.calculateHmac(ackPayload, secretKey)
                        "OK:tx_id=$txId:device_id=$targetDev:amount=$amountPulses:ts=$ackNow:v_sig=$ackSig"
                    } else {
                        "OK"
                    }

                    when (result) {
                        PaymentResult.APPLIED -> {
                            createResponse(Response.Status.OK, "text/plain", ackResp)
                        }
                        PaymentResult.ALREADY_APPLIED -> {
                            // Acknowledge duplicate with context if present so ESP32 clears retry queue
                            val resp = if (targetDev.isNotBlank()) "ALREADY_PROCESSED:$ackResp" else "ALREADY_PROCESSED"
                            createResponse(Response.Status.OK, "text/plain", resp)
                        }
                        PaymentResult.CONFLICT -> {
                            Log.w(TAG, "Payment rejected due to conflicting values for $txId")
                            createResponse(Response.Status.CONFLICT, "text/plain", "CONFLICT")
                        }
                        PaymentResult.NOT_ELIGIBLE -> {
                            Log.w(TAG, "Payment rejected: device not eligible for $txId")
                            createResponse(Response.Status.FORBIDDEN, "text/plain", "NOT_ELIGIBLE")
                        }
                        PaymentResult.FAILED -> {
                            Log.e(TAG, "Payment failed to commit to database for $txId")
                            createResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "SERVICE_UNAVAILABLE")
                        }
                    }
                } else if (rawSecondsLong < 0) {
                    val positiveSecondsLong = if (rawSecondsLong == Long.MIN_VALUE) Long.MAX_VALUE else -rawSecondsLong
                    if (positiveSecondsLong > Int.MAX_VALUE.toLong()) {
                        Log.w(TAG, "Rejecting deduction: seconds parameter magnitude exceeds Int.MAX_VALUE ($rawSecondsLong)")
                        return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
                    }
                    val positiveSeconds = positiveSecondsLong.toInt()
                    val deductTxId = txId ?: "deduct_${System.currentTimeMillis()}"
                    delegate.onDeductTime(positiveSeconds, deductTxId)
                    createResponse(Response.Status.OK, "text/plain", "OK")
                } else {
                    createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
                }
            }
            "/config" -> {
                val price = decryptedParams["price"]?.toDoubleOrNull()
                val minutes = decryptedParams["minutes"]?.toIntOrNull()
                val devName = decryptedParams["device_name"]?.trim()
                val pin = decryptedParams["admin_pin"]
                val slot = decryptedParams["slot"]?.toIntOrNull() ?: decryptedParams["slot_num"]?.toIntOrNull()
                // the box sends the branding in a push of its own (its params buffer is small): such a push is not a config update, so
                // it does not rewrite the slot or show the "Config Synced" toast. Any other push is handled exactly as before.
                val brandingSent = BRANDING_PARAMS.any { decryptedParams.containsKey(it) }
                val configSent = CONFIG_PARAMS.any { decryptedParams.containsKey(it) }
                if (configSent || !brandingSent) delegate.onConfigUpdated(price, minutes, devName, pin, slot)
                // the four branding settings are optional: a box that does not know them sends none and nothing changes
                if (brandingSent) {
                    delegate.onBrandingUpdated(
                        palette = decodeBrandingValue(decryptedParams["palette"]),
                        shopName = decodeBrandingValue(decryptedParams["shop_name"]),
                        title = decodeBrandingValue(decryptedParams["brand_title"]),
                        density = decodeBrandingValue(decryptedParams["anim_density"]),
                        deviceLabel = decodeBrandingValue(decryptedParams["device_label"]),
                    )
                }
                createResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/trigger_action" -> {
                val actionType = decryptedParams["action"] ?: ""
                // The box only ever sends the actions below. Destructive ones (factory reset, removing device
                // owner, exiting the kiosk, enabling USB debugging) are never reachable over the network:
                // they need the admin PIN on the phone itself or through the PIN-protected broadcast.
                if (actionType !in NETWORK_ACTIONS) {
                    Log.w(TAG, "Rejected network action '$actionType'")
                    return createResponse(Response.Status.FORBIDDEN, "text/plain", "NOT_ALLOWED_OVER_NETWORK")
                }
                val slot = decryptedParams["slot"]?.toIntOrNull() ?: decryptedParams["slot_num"]?.toIntOrNull()
                delegate.onTriggerAction(actionType, slot, decryptedParams)
                createResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/voice" -> {
                if (!delegate.isReady()) {
                    createResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "INITIALIZING")
                } else {
                    // The signed request has passed every check above (signature, time, once only). Its body is the frames of the
                    // message: read here, with a known length (NanoHTTPD 2.3.1 has no chunked requests). Nothing plays unless all checks.
                    val contentLength = session.headers["content-length"]?.trim()?.toLongOrNull() ?: -1L
                    val envelopeHmac = hmac.trim().lowercase()
                    val outcome = VoiceMessageReceiver.receive(secretKey, envelopeHmac, decryptedParams, contentLength, session.inputStream)
                    when (outcome) {
                        is VoiceMessageReceiver.Outcome.Accepted -> {
                            delegate.onVoiceMessage(outcome.samples)
                            createResponse(Response.Status.OK, "text/plain", "OK")
                        }
                        is VoiceMessageReceiver.Outcome.Refused -> {
                            Log.w(TAG, "Refused a voice message: ${outcome.word}", outcome.cause)
                            createResponse(Response.Status.BAD_REQUEST, "text/plain", outcome.word)
                        }
                    }
                }
            }
            "/crash" -> {
                val crashText = delegate.getCrashLog()
                if (crashText != null) {
                    createResponse(Response.Status.OK, "text/plain", crashText)
                } else {
                    createResponse(Response.Status.NOT_FOUND, "text/plain", "No crash log found")
                }
            }
            else -> createResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun serveParentDashboard(
        session: IHTTPSession,
        uri: String,
        params: Map<String, String>,
        clientIp: String,
    ): Response {
        val isHome = KioskSettings.isHomeMode(context)
        if (!TailscalePolicy.isParentDashboardAllowed(clientIp, isHome)) {
            Log.w(TAG, "Blocked /parent request from unauthorized client IP: $clientIp (isHomeMode=$isHome)")
            return createResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                "Access to the parent dashboard is permitted over Tailscale only.",
            )
        }

        // Parent PIN authentication
        val pin = session.headers["x-parent-pin"]
            ?: params["pin"]
            ?: session.cookies?.read("parent_pin")
            ?: ""
        val expectedPin = KioskSecurity.getAdminPin(context)
        val isAuthorized = expectedPin.isNotBlank() && KioskSecurity.constantTimeEquals(pin, expectedPin)

        if (!isAuthorized) {
            if (uri == "/parent/status" || uri == "/parent/action") {
                return createResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    JSONObject().put("success", false).put("error", "INVALID_PIN").toString(),
                )
            }
            return createResponse(Response.Status.OK, "text/html; charset=utf-8", renderPinUnlockHtml(pin.isNotBlank()))
        }

        return when (uri) {
            "/parent/status" -> {
                val curfew = KioskSettings.getCurfewSchedule(context)
                val cal = java.util.Calendar.getInstance()
                val minOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                val goal = KioskSettings.getPiggyBankGoal(context)
                val chores = KioskSettings.getChores(context)
                val allowed = KioskSettings.getAllowedApps(context)
                val statusObj = JSONObject().apply {
                    put("success", true)
                    put("device_name", KioskSecurity.getDeviceAlias(context).ifBlank { "PisoKid" })
                    put("state", delegate.getAppState())
                    put("time_remaining_sec", delegate.getSessionTimeRemaining())
                    put(
                        "curfew",
                        JSONObject().apply {
                            put("enabled", curfew.enabled)
                            put("start_min", curfew.startMinuteOfDay)
                            put("end_min", curfew.endMinuteOfDay)
                            put("is_active", curfew.isCurfewActive(minOfDay))
                        },
                    )
                    put(
                        "piggy",
                        JSONObject().apply {
                            put("target_pesos", goal.targetPesos)
                            put("label", goal.label)
                        },
                    )
                    val choresArr = JSONArray()
                    for (c in chores) {
                        choresArr.put(
                            JSONObject().apply {
                                put("id", c.id)
                                put("title", c.title)
                                put("reward_min", c.rewardMinutes)
                                put("status", c.status.name)
                                put("repeat", c.repeatDaily)
                            },
                        )
                    }
                    put("chores", choresArr)
                    put("allowed_apps", JSONArray(allowed.toList()))
                    put("remote_ip", clientIp)
                }
                createResponse(Response.Status.OK, "application/json", statusObj.toString())
            }
            "/parent/action" -> {
                val action = params["action"] ?: ""
                when (action) {
                    "add_time" -> {
                        val minutes = params["minutes"]?.toIntOrNull() ?: 15
                        delegate.creditPayment("tx-parent-add-${System.currentTimeMillis()}", minutes * 60, 0.0)
                    }
                    "deduct_time" -> {
                        val minutes = params["minutes"]?.toIntOrNull() ?: 15
                        delegate.onDeductTime(minutes * 60)
                    }
                    "pause" -> delegate.onPauseSession()
                    "resume" -> delegate.onResumeSession()
                    "ring" -> delegate.onTriggerAction("locate")
                    "ring_stop" -> delegate.onTriggerAction("locate_stop")
                    "approve_chore" -> {
                        val choreId = params["chore_id"] ?: ""
                        val current = KioskSettings.getChores(context)
                        val target = current.find { it.id == choreId }
                        if (target != null) {
                            val updated = current.map { if (it.id == choreId) it.copy(status = ChoreStatus.APPROVED) else it }
                            KioskSettings.setChores(context, updated)
                            delegate.creditPayment("tx-chore-$choreId-${System.currentTimeMillis()}", target.rewardMinutes * 60, 0.0)
                        }
                    }
                    "add_chore" -> {
                        val title = params["title"]?.trim() ?: "Chore"
                        val reward = params["reward"]?.toIntOrNull() ?: 15
                        val repeat = params["repeat"]?.toBoolean() ?: true
                        val current = KioskSettings.getChores(context)
                        val newChore = ChoreItem("c_${System.currentTimeMillis()}", title, reward, ChoreStatus.TODO, repeat)
                        KioskSettings.setChores(context, current + newChore)
                    }
                    "delete_chore" -> {
                        val choreId = params["chore_id"] ?: ""
                        val current = KioskSettings.getChores(context)
                        KioskSettings.setChores(context, current.filter { it.id != choreId })
                    }
                    "update_curfew" -> {
                        val enabled = params["enabled"]?.toBoolean() ?: false
                        val start = params["start"]?.toIntOrNull() ?: (21 * 60)
                        val end = params["end"]?.toIntOrNull() ?: (6 * 60)
                        KioskSettings.setCurfewSchedule(context, CurfewSchedule(enabled, start, end))
                    }
                    "update_goal" -> {
                        val target = params["target"]?.toDoubleOrNull() ?: 500.0
                        val label = params["label"]?.trim() ?: "Family Treat"
                        KioskSettings.setPiggyBankGoal(context, PiggyBankGoal(target, label))
                    }
                }
                createResponse(Response.Status.OK, "application/json", "{\"success\":true}")
            }
            else -> {
                val resp = createResponse(Response.Status.OK, "text/html; charset=utf-8", renderParentDashboardHtml(pin))
                resp.addHeader("Set-Cookie", "parent_pin=$pin; Path=/; SameSite=Strict")
                resp
            }
        }
    }

    private fun renderPinUnlockHtml(failed: Boolean): String {
        val errBlock = if (failed) "<div class=\"err\">Incorrect PIN. Try again.</div>" else ""
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PisoHome Parent Console</title>
            <style>
            body{margin:0;padding:24px;background:#0F172A;color:#F8FAFC;font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;box-sizing:border-box;}
            .card{background:#1E293B;border-radius:16px;padding:32px;max-width:360px;width:100%;box-shadow:0 10px 25px rgba(0,0,0,0.5);text-align:center;}
            h1{margin:0 0 8px 0;font-size:24px;color:#10B981;}
            p{color:#94A3B8;font-size:14px;margin:0 0 24px 0;}
            input{width:100%;padding:14px;border-radius:10px;border:1px solid #334155;background:#0F172A;color:#fff;font-size:20px;text-align:center;letter-spacing:4px;box-sizing:border-box;margin-bottom:16px;}
            button{width:100%;padding:14px;border-radius:10px;border:none;background:#10B981;color:#fff;font-size:16px;font-weight:600;cursor:pointer;}
            .err{color:#EF4444;font-size:13px;margin-bottom:12px;}
            </style>
            </head>
            <body>
            <div class="card">
            <h1>🛡️ PisoHome</h1>
            <p>Enter Parent PIN to unlock remote management</p>
            $errBlock
            <form method="GET" action="/parent">
            <input type="password" name="pin" maxlength="12" placeholder="••••" required autofocus pattern="[0-9]*" inputmode="numeric">
            <button type="submit">Unlock Console</button>
            </form>
            </div>
            </body>
            </html>
            """.trimIndent()
    }

    private fun renderParentDashboardHtml(pin: String): String = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PisoHome · Family Console</title>
            <style>
            :root{--emerald:#10B981;--emerald-dark:#059669;--gold:#F59E0B;--bg:#0F172A;--card:#1E293B;--border:#334155;--text:#F8FAFC;--sub:#94A3B8;}
            body{margin:0;padding:16px;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased;}
            .top-bar{display:flex;justify-content:space-between;align-items:center;padding:12px 16px;background:var(--card);border-radius:12px;margin-bottom:16px;}
            .logo{font-size:18px;font-weight:700;color:var(--emerald);display:flex;align-items:center;gap:8px;}
            .badge{background:#064E3B;color:#34D399;padding:4px 8px;border-radius:6px;font-size:12px;font-weight:600;}
            .tabs{display:flex;gap:8px;margin-bottom:16px;overflow-x:auto;}
            .tab-btn{flex:1;padding:12px;border:none;background:var(--card);color:var(--sub);border-radius:10px;font-size:14px;font-weight:600;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:6px;}
            .tab-btn.active{background:var(--emerald);color:#fff;}
            .panel{display:none;}
            .panel.active{display:block;}
            .card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:20px;margin-bottom:16px;}
            .card-title{font-size:16px;font-weight:700;margin:0 0 12px 0;display:flex;justify-content:space-between;align-items:center;}
            .hero-timer{text-align:center;padding:24px 0;}
            .time-big{font-size:48px;font-weight:800;color:var(--emerald);letter-spacing:1px;font-variant-numeric:tabular-nums;}
            .btn-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:16px;}
            .btn{padding:12px;border:none;border-radius:10px;font-size:14px;font-weight:600;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:6px;}
            .btn-emerald{background:var(--emerald);color:#fff;}
            .btn-gold{background:var(--gold);color:#000;}
            .btn-sub{background:#334155;color:var(--text);}
            .btn-danger{background:#EF4444;color:#fff;}
            .progress-bar{height:12px;background:#334155;border-radius:6px;overflow:hidden;margin:8px 0;}
            .progress-fill{height:100%;background:linear-gradient(90deg,var(--emerald),var(--gold));border-radius:6px;}
            .chore-item{display:flex;justify-content:space-between;align-items:center;padding:12px;border-bottom:1px solid var(--border);}
            .chore-title{font-weight:600;}
            .chore-reward{font-size:12px;color:var(--gold);}
            input,select{width:100%;padding:10px;border-radius:8px;border:1px solid var(--border);background:#0F172A;color:#fff;box-sizing:border-box;margin-bottom:10px;}
            </style>
            </head>
            <body>
            <div class="top-bar">
              <div class="logo">🛡️ PisoHome <span class="badge" id="devName">...</span></div>
              <div style="font-size:12px;color:var(--sub);" id="remoteStatus">Tailscale Connected</div>
            </div>

            <div class="tabs">
              <button class="tab-btn active" onclick="switchTab('home')">🏠 Home</button>
              <button class="tab-btn" onclick="switchTab('chores')">✅ Chores</button>
              <button class="tab-btn" onclick="switchTab('settings')">⚙️ Settings</button>
            </div>

            <div id="tab-home" class="panel active">
              <div class="card">
                <div class="card-title"><span>Liam's Screen Time</span><span class="badge" id="stateBadge">Active</span></div>
                <div class="hero-timer">
                  <div class="time-big" id="timeRemaining">--:--</div>
                  <div style="font-size:13px;color:var(--sub);" id="curfewNote">No active curfew</div>
                </div>
                <div class="btn-grid">
                  <button class="btn btn-emerald" onclick="doAction('add_time','minutes=15')">+15 Min</button>
                  <button class="btn btn-sub" onclick="doAction('deduct_time','minutes=15')">-15 Min</button>
                  <button class="btn btn-gold" id="btnPauseResume" onclick="togglePauseResume()">⏸️ Pause Time</button>
                  <button class="btn btn-sub" onclick="doAction('ring','')">🔔 Ring Phone</button>
                </div>
              </div>

              <div class="card" id="pendingCard" style="display:none;">
                <div class="card-title" style="color:var(--gold);">Pending Approvals</div>
                <div id="pendingList"></div>
              </div>

              <div class="card">
                <div class="card-title"><span>Family Piggy Bank</span><span id="piggyLabel" style="font-size:13px;color:var(--sub);"></span></div>
                <div class="progress-bar"><div class="progress-fill" id="piggyFill" style="width:0%;"></div></div>
                <div style="font-size:13px;color:var(--sub);text-align:right;" id="piggyText">₱0 / ₱500</div>
              </div>
            </div>

            <div id="tab-chores" class="panel">
              <div class="card">
                <div class="card-title">Quest & Chore Board</div>
                <div id="choresList"></div>
                <hr style="border:0;border-top:1px solid var(--border);margin:16px 0;">
                <div style="font-weight:600;margin-bottom:8px;">Add New Quest</div>
                <input type="text" id="newChoreTitle" placeholder="e.g. Wash Dishes">
                <select id="newChoreReward"><option value="15">+15 Minutes</option><option value="20">+20 Minutes</option><option value="30">+30 Minutes</option><option value="60">+60 Minutes</option></select>
                <button class="btn btn-emerald" style="width:100%;" onclick="addChore()">Add Quest</button>
              </div>

              <div class="card">
                <div class="card-title">Bedtime & Curfew Schedule</div>
                <label style="display:flex;align-items:center;gap:8px;margin-bottom:12px;font-size:14px;"><input type="checkbox" id="curfewEnabled" style="width:auto;margin:0;"> Enable Curfew Lock</label>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;">
                  <div><span style="font-size:12px;color:var(--sub);">Start (Bedtime)</span><input type="time" id="curfewStart"></div>
                  <div><span style="font-size:12px;color:var(--sub);">End (Morning)</span><input type="time" id="curfewEnd"></div>
                </div>
                <button class="btn btn-sub" style="width:100%;margin-top:10px;" onclick="saveCurfew()">Save Curfew</button>
              </div>
            </div>

            <div id="tab-settings" class="panel">
              <div class="card">
                <div class="card-title">Savings Goal</div>
                <input type="text" id="goalLabel" placeholder="e.g. Weekend Pizza">
                <input type="number" id="goalTarget" placeholder="Target Pesos (e.g. 500)">
                <button class="btn btn-sub" style="width:100%;" onclick="saveGoal()">Update Goal</button>
              </div>
              <div class="card">
                <div class="card-title">Tailscale Remote Access</div>
                <p style="font-size:13px;color:var(--sub);margin:0 0 8px 0;">Connected via Tailscale Always-On VPN (100.64.0.0/10).</p>
                <div style="font-size:13px;font-family:monospace;background:#0F172A;padding:8px;border-radius:6px;" id="tailscaleInfo">IP: ...</div>
              </div>
            </div>

            <script>
            const PIN = '$pin';
            let currentState = 0;
            function switchTab(t){
              document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
              document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
              document.getElementById('tab-' + t).classList.add('active');
              event.target.classList.add('active');
            }
            function fmtTime(s){
              const m = Math.floor(s/60), sec = s%60;
              return m + 'm ' + (sec<10?'0':'') + sec + 's';
            }
            function poll(){
              fetch('/parent/status?pin=' + encodeURIComponent(PIN))
                .then(r => r.json())
                .then(d => {
                  if(!d.success) return;
                  document.getElementById('devName').textContent = d.device_name;
                  currentState = d.state;
                  document.getElementById('timeRemaining').textContent = fmtTime(d.time_remaining_sec);
                  const isUnlocked = (d.state === 2 || d.state === 3);
                  document.getElementById('stateBadge').textContent = isUnlocked ? 'Active' : (d.curfew && d.curfew.is_active ? 'Curfew' : 'Locked');
                  document.getElementById('stateBadge').style.background = isUnlocked ? '#064E3B' : '#7F1D1D';
                  document.getElementById('stateBadge').style.color = isUnlocked ? '#34D399' : '#FCA5A5';
                  document.getElementById('btnPauseResume').textContent = isUnlocked ? '⏸️ Pause Time' : '▶️ Resume Time';

                  if (d.curfew && d.curfew.is_active) {
                    document.getElementById('curfewNote').textContent = '🌙 Curfew Active (Phone Locked)';
                  } else {
                    document.getElementById('curfewNote').textContent = d.curfew && d.curfew.enabled ? 'Curfew scheduled tonight' : 'No active curfew';
                  }

                  // Pending approvals
                  const pending = (d.chores || []).filter(c => c.status === 'PENDING_APPROVAL');
                  const pCard = document.getElementById('pendingCard');
                  if (pending.length > 0) {
                    pCard.style.display = 'block';
                    document.getElementById('pendingList').innerHTML = pending.map(c => 
                      '<div class="chore-item"><div><div class="chore-title">' + c.title + '</div><div class="chore-reward">Reward: +' + c.reward_min + 'm</div></div>' +
                      '<button class="btn btn-emerald" onclick="doAction(\'approve_chore\',\'chore_id=' + c.id + '\')">Approve +' + c.reward_min + 'm</button></div>'
                    ).join('');
                  } else {
                    pCard.style.display = 'none';
                  }

                  // Chores list
                  document.getElementById('choresList').innerHTML = (d.chores || []).map(c => 
                    '<div class="chore-item"><div><div class="chore-title">' + c.title + ' ' + (c.status === 'APPROVED' ? '✅' : '') + '</div>' +
                    '<div class="chore-reward">+' + c.reward_min + 'm · ' + (c.repeat ? 'Daily' : 'One-time') + '</div></div>' +
                    '<button class="btn btn-danger" style="padding:6px 10px;font-size:12px;" onclick="doAction(\'delete_chore\',\'chore_id=' + c.id + '\')">✕</button></div>'
                  ).join('');

                  // Piggy Bank
                  if (d.piggy) {
                    document.getElementById('piggyLabel').textContent = d.piggy.label;
                    document.getElementById('piggyText').textContent = 'Target: ₱' + d.piggy.target_pesos;
                    document.getElementById('piggyFill').style.width = '30%';
                  }
                  document.getElementById('tailscaleInfo').textContent = 'Remote IP: ' + (d.remote_ip || '100.x.y.z');
                }).catch(() => {});
            }
            function doAction(act, extra){
              fetch('/parent/action?pin=' + encodeURIComponent(PIN) + '&action=' + act + (extra ? '&' + extra : ''), {method:'POST'})
                .then(r => r.json())
                .then(() => poll());
            }
            function togglePauseResume(){
              const isUnlocked = (currentState === 2 || currentState === 3);
              doAction(isUnlocked ? 'pause' : 'resume', '');
            }
            function addChore(){
              const t = document.getElementById('newChoreTitle').value.trim();
              const r = document.getElementById('newChoreReward').value;
              if(!t) return;
              doAction('add_chore', 'title=' + encodeURIComponent(t) + '&reward=' + r + '&repeat=true');
              document.getElementById('newChoreTitle').value = '';
            }
            function saveCurfew(){
              const en = document.getElementById('curfewEnabled').checked;
              const sVal = document.getElementById('curfewStart').value.split(':');
              const eVal = document.getElementById('curfewEnd').value.split(':');
              const sMin = (parseInt(sVal[0]||'21',10)*60) + parseInt(sVal[1]||'0',10);
              const eMin = (parseInt(eVal[0]||'6',10)*60) + parseInt(eVal[1]||'0',10);
              doAction('update_curfew', 'enabled=' + en + '&start=' + sMin + '&end=' + eMin);
            }
            function saveGoal(){
              const lbl = document.getElementById('goalLabel').value.trim();
              const tgt = document.getElementById('goalTarget').value;
              doAction('update_goal', 'label=' + encodeURIComponent(lbl) + '&target=' + encodeURIComponent(tgt));
            }
            setInterval(poll, 3000);
            poll();
            </script>
            </body>
            </html>
        """.trimIndent()
}
