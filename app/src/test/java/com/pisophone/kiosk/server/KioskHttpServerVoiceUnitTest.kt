package com.pisophone.kiosk.server

import com.pisophone.kiosk.network.BoxClock
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.voice.VoiceClip
import com.pisophone.kiosk.voice.VoiceTestSupport
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The phone's /voice route (feature 056) through the real server: a signed request with the frames of a voice message in its body. Only
 * a message that is signed, fresh, new, the size it announced and whole reaches the delegate; everything else plays nothing, and money
 * and time are never touched. (What the route decides about the frames is tested on its own in VoiceMessageReceiverTest.)
 */
@RunWith(RobolectricTestRunner::class)
class KioskHttpServerVoiceUnitTest {
    private val testSecret = "0123456789abcdef0123456789abcdef"
    private val boxNow = 1_790_000_000_000L
    private val played = mutableListOf<ShortArray>()
    private var ready = true
    private var creditCalls = 0
    private var deductCalls = 0
    private var triggerCalls = 0
    private var nonce = 0

    private val delegate = object : KioskServerDelegate {
        override fun isReady(): Boolean = ready
        override fun getSecretKey(): String = testSecret
        override fun onHeartbeat(clientIp: String?) {}
        override fun getStatusJson(): JSONObject = JSONObject()
        override fun getAuditEventsJson(): String = "[]"
        override fun getSessionTimeRemaining(): Int = 300
        override fun getAppState(): Int = 2
        override fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult {
            creditCalls++
            return PaymentResult.APPLIED
        }
        override fun onDeductTime(seconds: Int, txId: String?) {
            deductCalls++
        }
        override fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int?) {}
        override fun onTriggerAction(action: String, slotNum: Int?, extra: Map<String, String>?) {
            triggerCalls++
        }
        override fun getCrashLog(): String? = null
        override fun onVoiceMessage(samples: ShortArray) {
            played += samples
        }
    }

    private lateinit var server: KioskHttpServer

    @Before
    fun setUp() {
        BoxClock.reset()
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        server = KioskHttpServer(context, 8080, delegate, RequestFreshness({ boxNow }, { true }))
    }

    @After
    fun tearDown() = BoxClock.reset()

    private class VoiceRequest(val params: Map<String, String>, val body: ByteArray, val headers: Map<String, String>)

    /** A request as the box makes it: the signed envelope in the URL parameters, the frames chained from its hmac as the body. */
    private fun voiceRequest(
        clip: ByteArray,
        ts: Long = boxNow,
        announced: Int = clip.size,
        alter: (ByteArray) -> ByteArray = { it },
    ): VoiceRequest {
        val query = "action=voice_message&ts=$ts&nonce=vm-${nonce++}&bytes=$announced"
        val payload = KioskSecurity.encrypt(query, testSecret)
        val hmac = KioskSecurity.calculateHmac(payload, testSecret)
        val body = alter(VoiceTestSupport.wire(VoiceTestSupport.frames(clip, testSecret, hmac)))
        return VoiceRequest(
            params = mapOf("payload" to payload, "hmac" to hmac),
            body = body,
            headers = mapOf("remote-addr" to "192.168.4.2", "content-length" to body.size.toString()),
        )
    }

    private fun session(
        uri: String,
        request: VoiceRequest,
        headers: Map<String, String> = request.headers,
    ): NanoHTTPD.IHTTPSession = object : NanoHTTPD.IHTTPSession {
        override fun execute() {}
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = headers
        override fun getInputStream(): InputStream = ByteArrayInputStream(request.body)
        override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.POST
        override fun getParms(): Map<String, String> = request.params
        override fun getParameters(): Map<String, List<String>> = request.params.mapValues { listOf(it.value) }
        override fun getQueryParameterString(): String = ""
        override fun getUri(): String = uri
        override fun parseBody(files: MutableMap<String, String>?) {}
        override fun getRemoteIpAddress(): String = headers["remote-addr"] ?: "127.0.0.1"
        override fun getRemoteHostName(): String = "test-host"
    }

    private fun send(request: VoiceRequest, headers: Map<String, String> = request.headers): NanoHTTPD.Response =
        server.serve(session("/voice", request, headers))

    private fun body(r: NanoHTTPD.Response) = r.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""

    private val clip get() = VoiceTestSupport.clipNamed("sawtooth-0.5s")

    private fun assertNothingPlayedAndNoMoneyOrTimeTouched() {
        assertTrue("nothing plays", played.isEmpty())
        assertNoMoneyOrTimeTouched()
    }

    private fun assertNoMoneyOrTimeTouched() {
        assertEquals("no credit", 0, creditCalls)
        assertEquals("no deduction", 0, deductCalls)
        assertEquals("no action (lock, unlock, coin slot)", 0, triggerCalls)
    }

    @Test
    fun aGoodMessageIsPlayedOnceAndTouchesNothingElse() {
        val r = send(voiceRequest(clip))
        assertEquals(200, r.status.requestStatus)
        assertEquals("OK", body(r))
        assertEquals(1, played.size)
        val expected = (VoiceClip.decode(clip) as VoiceClip.Decoded.Ok).samples
        assertTrue(expected.contentEquals(played[0]))
        assertNoMoneyOrTimeTouched()
    }

    @Test
    fun theLongestMessageIsPlayed() {
        val longest = VoiceTestSupport.clipNamed("max-15s")
        val r = send(voiceRequest(longest))
        assertEquals(200, r.status.requestStatus)
        assertEquals(VoiceClip.MAX_SAMPLES, played.single().size)
    }

    @Test
    fun anUnsignedRequestPlaysNothing() {
        val good = voiceRequest(clip)
        val noPayload = VoiceRequest(emptyMap(), good.body, good.headers)
        assertEquals(401, send(noPayload).status.requestStatus)
        val noHmac = VoiceRequest(good.params - "hmac", good.body, good.headers)
        assertEquals(401, send(noHmac).status.requestStatus)
        val wrongHmac = VoiceRequest(good.params + ("hmac" to "0".repeat(64)), good.body, good.headers)
        assertEquals(401, send(wrongHmac).status.requestStatus)
        assertNothingPlayedAndNoMoneyOrTimeTouched()
    }

    @Test
    fun aStaleRequestPlaysNothing() {
        val r = send(voiceRequest(clip, ts = boxNow - 10 * 60 * 1000L))
        assertEquals(403, r.status.requestStatus)
        assertEquals("STALE_TIMESTAMP", body(r))
        assertNothingPlayedAndNoMoneyOrTimeTouched()
    }

    @Test
    fun aRepeatOfTheSameRequestPlaysOnlyOnce() {
        val request = voiceRequest(clip)
        assertEquals("OK", body(send(request)))
        assertEquals("OK:DUPLICATE", body(send(request)))
        assertEquals(1, played.size)
    }

    @Test
    fun aMessageWhoseSizeOrLengthDoesNotMatchPlaysNothing() {
        val wrongLength = voiceRequest(clip)
        val r = send(wrongLength, wrongLength.headers + ("content-length" to (wrongLength.body.size + 1).toString()))
        assertEquals(400, r.status.requestStatus)
        assertEquals("VOICE_BAD_LENGTH", body(r))
        val noLength = voiceRequest(clip)
        assertEquals("VOICE_BAD_LENGTH", body(send(noLength, noLength.headers - "content-length")))
        assertEquals("VOICE_BAD_SIZE", body(send(voiceRequest(clip, announced = VoiceClip.MAX_CLIP_BYTES + 1))))
        assertNothingPlayedAndNoMoneyOrTimeTouched()
    }

    @Test
    fun aMessageThatIsCutOrAlteredPlaysNothing() {
        // The request announces the full length, but only part of the body arrives (the box lost the phone halfway).
        val full = voiceRequest(clip)
        val cut = VoiceRequest(full.params, full.body.copyOf(full.body.size - 10), full.headers)
        assertEquals("VOICE_FRAMES_short", body(send(cut)))
        val altered = voiceRequest(clip, alter = { it.copyOf().also { b -> b[40] = (b[40].toInt() xor 1).toByte() } })
        assertEquals("VOICE_FRAMES_mac", body(send(altered)))
        assertNothingPlayedAndNoMoneyOrTimeTouched()
    }

    @Test
    fun aMessageFromAnotherSignedRequestPlaysNothing() {
        // Frames chained from a different request's hmac: they fit by size but not by the chain.
        val first = voiceRequest(clip)
        val second = voiceRequest(clip)
        val mixed = VoiceRequest(second.params, first.body, first.headers)
        assertEquals("VOICE_FRAMES_mac", body(send(mixed)))
        assertNothingPlayedAndNoMoneyOrTimeTouched()
    }

    @Test
    fun aServerThatIsNotReadyAnswersBusyAndPlaysNothing() {
        ready = false
        assertEquals(503, send(voiceRequest(clip)).status.requestStatus)
        assertNothingPlayedAndNoMoneyOrTimeTouched()
    }
}
