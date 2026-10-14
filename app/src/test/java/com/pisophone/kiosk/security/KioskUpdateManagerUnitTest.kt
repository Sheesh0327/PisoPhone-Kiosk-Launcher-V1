package com.pisophone.kiosk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KioskUpdateManagerUnitTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesPublishedVersionInfo() {
        val v = KioskUpdateManager.parseRemoteVersion(
            """{"versionCode": 42, "versionName": "1.0.42", "sha256": "ABCDEF"}""",
        )
        assertNotNull(v)
        assertEquals(42, v!!.versionCode)
        assertEquals("abcdef", v.sha256)
    }

    @Test
    fun rejectsMalformedOrMissingVersionInfo() {
        assertNull(KioskUpdateManager.parseRemoteVersion("<html>Not found</html>"))
        assertNull(KioskUpdateManager.parseRemoteVersion("{}"))
        assertNull(KioskUpdateManager.parseRemoteVersion("""{"versionCode": 0}"""))
    }

    @Test
    fun onlyStrictlyNewerBuildsAreUpdates() {
        assertTrue(KioskUpdateManager.isUpdateAvailable(43, 42))
        assertFalse("identical build must be skipped", KioskUpdateManager.isUpdateAvailable(42, 42))
        assertFalse("older build must be skipped", KioskUpdateManager.isUpdateAvailable(41, 42))
    }

    @Test
    fun zipHeaderCheckAcceptsApkAndRejectsHtml() {
        val apk = tmp.newFile("a.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)) }
        val html = tmp.newFile("b.apk").apply { writeText("<!doctype html><html>Pages fallback</html>") }
        val tiny = tmp.newFile("c.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        assertTrue(KioskUpdateManager.hasZipHeader(apk))
        assertFalse(KioskUpdateManager.hasZipHeader(html))
        assertFalse(KioskUpdateManager.hasZipHeader(tiny))
    }

    @Test
    fun sha256MatchesKnownValue() {
        val f = tmp.newFile("x.bin").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            KioskUpdateManager.sha256Hex(f),
        )
    }

    @Test
    fun parsesTheDownloadUrlWhenPublished() {
        val v = KioskUpdateManager.parseRemoteVersion(
            """{"versionCode": 7, "sha256": "ab", "url": " https://example.test/app.apk "}""",
        )
        assertEquals("https://example.test/app.apk", v!!.url)
        assertEquals("", KioskUpdateManager.parseRemoteVersion("""{"versionCode": 7}""")!!.url)
    }

    @Test
    fun aChecksumIsMandatoryAndMustBeHex() {
        val good = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(KioskUpdateManager.isValidSha256(good))
        assertFalse(KioskUpdateManager.isValidSha256(""))
        assertFalse(KioskUpdateManager.isValidSha256(good.substring(1)))
        assertFalse(KioskUpdateManager.isValidSha256(good.uppercase()))
        assertFalse(KioskUpdateManager.isValidSha256("g".repeat(64)))
    }

    @Test
    fun onlyHttpsDownloadsFromTheUpdateSiteOrGithubAreAllowed() {
        val base = "https://pisophone.pages.dev/update"
        assertTrue(KioskUpdateManager.isAllowedDownloadUrl("https://pisophone.pages.dev/update/app-release.apk", base))
        assertTrue(
            KioskUpdateManager.isAllowedDownloadUrl("https://github.com/owner/releases/releases/download/app-stable-9/a.apk", base),
        )
        assertFalse(KioskUpdateManager.isAllowedDownloadUrl("http://pisophone.pages.dev/update/app.apk", base))
        assertFalse(KioskUpdateManager.isAllowedDownloadUrl("file:///sdcard/app.apk", base))
        assertFalse(KioskUpdateManager.isAllowedDownloadUrl("", base))
        assertFalse("some other server", KioskUpdateManager.isAllowedDownloadUrl("https://example.test/app.apk", base))
        assertFalse("user-info trick", KioskUpdateManager.isAllowedDownloadUrl("https://github.com@example.test/app.apk", base))
        assertFalse("look-alike host", KioskUpdateManager.isAllowedDownloadUrl("https://github.com.example.test/app.apk", base))
        assertFalse("odd port", KioskUpdateManager.isAllowedDownloadUrl("https://github.com:8443/app.apk", base))
    }

    @Test
    fun aPhoneOnlyFollowsItsOwnChannel() {
        assertTrue(KioskUpdateManager.isRightChannel("stable", "stable"))
        assertTrue(KioskUpdateManager.isRightChannel("dev", "dev"))
        assertTrue("older app.json without the field", KioskUpdateManager.isRightChannel("", "stable"))
        assertFalse(KioskUpdateManager.isRightChannel("dev", "stable"))
        assertFalse(KioskUpdateManager.isRightChannel("stable", "dev"))
    }

    @Test
    fun parsesTheChannel() {
        val v = KioskUpdateManager.parseRemoteVersion("""{"versionCode": 7, "sha256": "ab", "channel": "dev"}""")
        assertEquals("dev", v!!.channel)
    }
}
