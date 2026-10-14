package com.pisophone.kiosk.screenshot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The picture comparison itself: it must pass the same picture, ignore a whisper of difference, and fail a real change. */
class GoldenUnitTest {
    private lateinit var dir: File
    private val properties = listOf("screenshots.dir", "screenshots.diff.dir", "screenshots.update")
    private val saved = HashMap<String, String?>()
    private val width = 40
    private val height = 50

    @Before
    fun tempFolders() {
        // the settings are global to the test JVM, where the real screenshot tests run too: put them back afterwards
        properties.forEach { saved[it] = System.getProperty(it) }
        dir = Files.createTempDirectory("golden").toFile()
        System.setProperty("screenshots.dir", File(dir, "golden").path)
        System.setProperty("screenshots.diff.dir", File(dir, "diff").path)
        System.setProperty("screenshots.update", "false")
    }

    @After
    fun cleanUp() {
        properties.forEach { name -> saved[name]?.let { System.setProperty(name, it) } ?: System.clearProperty(name) }
        dir.deleteRecursively()
    }

    private fun picture(colour: Int = 0xFF204080.toInt(), block: (Int, Int) -> Int? = { _, _ -> null }) =
        IntArray(width * height) { i -> block(i % width, i / width) ?: colour }

    private fun fails(argb: IntArray): Boolean = try {
        Golden.check("shot", width, height, argb)
        false
    } catch (e: AssertionError) {
        true
    }

    @Test
    fun theFirstTimeThePictureIsRecordedThenItMustMatch() {
        Golden.check("shot", width, height, picture())
        assertTrue("recorded", File(dir, "golden/shot.png").exists())
        assertEquals(false, fails(picture()))
    }

    @Test
    fun aWhisperOfDifferenceIsIgnored() {
        Golden.check("shot", width, height, picture())
        // every pixel a little different (a different anti-aliasing): still the same screen
        assertEquals(false, fails(picture(colour = 0xFF214080.toInt())))
    }

    @Test
    fun aRealChangeFailsAndLeavesTheEvidence() {
        Golden.check("shot", width, height, picture())
        // a block of 12 x 12 pixels in another colour: 7% of the picture
        val changed = picture { x, y -> if (x in 5..16 && y in 5..16) 0xFFFFB81C.toInt() else null }
        assertTrue("a changed block fails", fails(changed))
        assertTrue(File(dir, "diff/shot.actual.png").exists())
        assertTrue(File(dir, "diff/shot.diff.png").exists())
    }

    @Test
    fun aDifferentSizeFails() {
        Golden.check("shot", width, height, picture())
        val taller = try {
            Golden.check("shot", width, height + 1, IntArray(width * (height + 1)))
            false
        } catch (e: AssertionError) {
            true
        }
        assertTrue(taller)
    }

    @Test
    fun updateReRecordsTheGolden() {
        Golden.check("shot", width, height, picture())
        System.setProperty("screenshots.update", "true")
        Golden.check("shot", width, height, picture(colour = 0xFFFFB81C.toInt()))
        System.setProperty("screenshots.update", "false")
        assertEquals("the new picture is the golden now", false, fails(picture(colour = 0xFFFFB81C.toInt())))
    }
}
