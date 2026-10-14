package com.pisophone.kiosk.screenshot

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Screenshot comparison without a plugin: a screen is drawn by Robolectric, and its pixels are compared with the golden PNG stored in
 * `app/src/test/screenshots/`.
 *
 *  - A golden that does not exist yet is recorded (the test passes; CI commits new goldens on a branch, and refuses them on main).
 *  - A golden that exists must match: a few pixels may differ by a little (anti-aliasing), nothing more. A mismatch writes the new
 *    image and a red-on-grey difference picture to `app/build/screenshot-diffs/` and fails.
 *  - A design change on purpose: run `./gradlew :app:testDebugUnitTest -Pscreenshots.update=true` and commit the changed PNGs.
 */
object Golden {
    /** A pixel counts as different when one colour channel differs by more than this. */
    private const val CHANNEL_TOLERANCE = 12

    /** At most this share of the pixels may differ. */
    private const val MAX_DIFFERENT_SHARE = 0.002

    /** Compares a picture the screenshot library took (a PNG file) with the golden of the same name. */
    fun checkFile(name: String, picture: File) {
        val image = ImageIO.read(picture)
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        check(name, image.width, image.height, argb)
    }

    fun check(name: String, width: Int, height: Int, argb: IntArray) {
        val dir = File(System.getProperty("screenshots.dir") ?: "src/test/screenshots")
        val golden = File(dir, "$name.png")
        val update = System.getProperty("screenshots.update") == "true"
        val actual = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, argb, 0, width) }

        if (!golden.exists() || update) {
            dir.mkdirs()
            ImageIO.write(actual, "png", golden)
            println("SCREENSHOT ${if (update) "re-recorded" else "recorded"}: ${golden.name}")
            return
        }

        val expected = ImageIO.read(golden)
        val diffDir = File(System.getProperty("screenshots.diff.dir") ?: "build/screenshot-diffs").apply { mkdirs() }
        if (expected.width != width || expected.height != height) {
            ImageIO.write(actual, "png", File(diffDir, "$name.actual.png"))
            throw AssertionError("$name: the screen is ${width}x$height, the golden is ${expected.width}x${expected.height}")
        }
        val diff = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var different = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = actual.getRGB(x, y)
                val e = expected.getRGB(x, y)
                if (differs(a, e)) {
                    different++
                    diff.setRGB(x, y, 0xFFFF0000.toInt())
                } else {
                    diff.setRGB(x, y, grey(e))
                }
            }
        }
        val share = different.toDouble() / (width * height)
        if (share > MAX_DIFFERENT_SHARE) {
            ImageIO.write(actual, "png", File(diffDir, "$name.actual.png"))
            ImageIO.write(diff, "png", File(diffDir, "$name.diff.png"))
            throw AssertionError("%s: %d pixels (%.2f%%) differ from the golden; see build/screenshot-diffs/".format(name, different, share * 100))
        }
    }

    private fun differs(a: Int, b: Int): Boolean {
        for (shift in intArrayOf(0, 8, 16, 24)) {
            if (kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) > CHANNEL_TOLERANCE) return true
        }
        return false
    }

    private fun grey(argb: Int): Int {
        val g = (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)) / 3
        val light = 200 + g / 5
        return (0xFF shl 24) or (light shl 16) or (light shl 8) or light
    }
}
