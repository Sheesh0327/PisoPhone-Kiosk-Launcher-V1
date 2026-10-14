package com.pisophone.kiosk.screenshot

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The base of the screenshot tests: a Robolectric phone (360 x 740 dp, 1.5x) with the animations turned off, so the lock screen
 * draws its still art and every picture is the same every time. A test says what to draw and gets the picture checked against its
 * golden (see [Golden]).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun animationsOff() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    /**
     * Draws [content] and checks the picture. Robolectric has no screen to copy pixels from, so the picture is taken by Roborazzi (which
     * draws the view hierarchy itself); what is stored and compared is ours: [Golden].
     */
    protected fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        compose.waitForIdle()
        val taken = File(System.getProperty("screenshots.diff.dir") ?: "build/screenshot-diffs", "$name.taken.png")
        taken.parentFile?.mkdirs()
        System.setProperty("roborazzi.test.record", "true") // "write the picture to this file"; we do the comparing
        compose.onRoot().captureRoboImage(taken.path)
        Golden.checkFile(name, taken)
        taken.delete()
    }
}
