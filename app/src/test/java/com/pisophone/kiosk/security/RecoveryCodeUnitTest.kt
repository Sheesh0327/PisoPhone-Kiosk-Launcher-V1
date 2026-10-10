package com.pisophone.kiosk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vectors come from scripts/make_recovery_code.py (a throwaway key), so Python and the app agree on the format. */
class RecoveryCodeUnitTest {
    private const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfjTvjxJ0qP9HOZEErHkh6QY4rxOS" +
            "p+Yn8G3QITPdb2bNvTniqcjcZglppVDWkJXQ8UcqBtdm3UcKxSUNwirJ5g=="

    private const val OTHER_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZrSDp/GzuwWCR0e+rohB2MMRp92S" +
            "JmofcYM/xTf1owjAC5oHoCxYO8NifmdNBP3eCRN/t6OsC9dgKj/h6T4abA=="

    private const val CODE =
        "PISOREC1.4102444800.MEQCIHPGzjYUGLX5wzlVSRmpz9fhZpREOvnn1e-mi2zR" +
            "KvQmAiBTvGXgnDRfa2-IYMT2MpsOtC9xiXq3GXzxlSJ6aJrouA"

    private const val CODE_TOO_FAR =
        "PISOREC1.4102588800.MEQCIAnb0Nc3-tJVxPqw-yq-GS-yifq_YOMUyjFgAvSY" +
            "v_VtAiADQiuFgy20Inefw4LsstMU3FL-DazTBF0BbqC3pxj57w"

    private val expiry = 4102444800L

    @Test
    fun aCodeSignedWithTheOwnerKeyIsAcceptedUntilItExpires() {
        assertEquals(RecoveryCode.Result.OK, RecoveryCode.verify(CODE, PUBLIC_KEY, expiry - 600))
        assertEquals(RecoveryCode.Result.OK, RecoveryCode.verify(CODE, PUBLIC_KEY, expiry))
        assertEquals(RecoveryCode.Result.EXPIRED, RecoveryCode.verify(CODE, PUBLIC_KEY, expiry + 1))
    }

    @Test
    fun aCodeThatLivesTooLongIsRefused() {
        assertEquals(RecoveryCode.Result.TOO_FAR, RecoveryCode.verify(CODE_TOO_FAR, PUBLIC_KEY, expiry - 600))
    }

    @Test
    fun anotherKeyOrANoKeyBuildNeverAccepts() {
        assertEquals(RecoveryCode.Result.BAD_SIGNATURE, RecoveryCode.verify(CODE, OTHER_PUBLIC_KEY, expiry - 600))
        assertEquals(RecoveryCode.Result.NO_KEY, RecoveryCode.verify(CODE, "", expiry - 600))
    }

    @Test
    fun aChangedExpiryBreaksTheSignature() {
        val forged = CODE.replace("4102444800", "4102444999")
        assertEquals(RecoveryCode.Result.BAD_SIGNATURE, RecoveryCode.verify(forged, PUBLIC_KEY, expiry - 600))
    }

    @Test
    fun otherQrCodesAreNotRecoveryCodes() {
        assertFalse(RecoveryCode.looksLikeRecovery("PISO1.84C7BBE15760.1.10800.abc"))
        assertFalse(RecoveryCode.looksLikeRecovery("https://example.com"))
        assertTrue(RecoveryCode.looksLikeRecovery(CODE))
        assertEquals(RecoveryCode.Result.NOT_RECOVERY, RecoveryCode.verify("hello", PUBLIC_KEY, 0))
    }

    @Test
    fun brokenCodesAreMalformedNotACrash() {
        assertEquals(RecoveryCode.Result.MALFORMED, RecoveryCode.verify("PISOREC1.abc.def", PUBLIC_KEY, 0))
        assertEquals(RecoveryCode.Result.MALFORMED, RecoveryCode.verify("PISOREC1.123", PUBLIC_KEY, 0))
        assertEquals(RecoveryCode.Result.MALFORMED, RecoveryCode.verify("PISOREC1.123.!!!", PUBLIC_KEY, 0))
        assertEquals(RecoveryCode.Result.BAD_SIGNATURE, RecoveryCode.verify("PISOREC1.123.AAAA", PUBLIC_KEY, 0))
    }
}
