package com.pisophone.kiosk.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.pisophone.kiosk.network.Esp32AccountRequests
import com.pisophone.kiosk.service.AccountController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The QR card scanner. A player holds their card up to the phone's camera; the card text goes to the box (through
 * [AccountController]), and on a card's first scan this screen then asks for a name for the account.
 *
 * A normal screen is used (not an overlay window) because the camera and the keyboard both work properly in one. While it is
 * open [AccountController.scanning] is true, which takes the lock screen and pill windows off so they do not cover the camera;
 * they come back when this screen closes. It closes by itself when nothing happens for a while, and whenever it loses the front.
 */
class CardScanActivity : ComponentActivity() {
    private enum class Mode { SCANNING, WORKING, NAME, MESSAGE }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var guide: View
    private lateinit var status: TextView
    private lateinit var nameBox: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var primaryButton: Button
    private lateinit var cancelButton: Button

    @Volatile private var mode = Mode.SCANNING
    private var ignoredSince = 0L
    private var finishing = false

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    private val timeout = Runnable { closeScreen() }

    private val askForCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showMessage("The camera is not allowed. Ask the attendant.", retry = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        analysisExecutor = Executors.newSingleThreadExecutor()
        AccountController.scanning.value = true
        buildUi()
        resetTimeout()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askForCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        super.onStop()
        // Home, the screen turning off, another app in front: the scanner never lingers with the camera open.
        closeScreen()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        AccountController.scanning.value = false
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        closeScreen()
    }

    // ---- screen ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        guide = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(4), Color.WHITE)
                cornerRadius = dp(24).toFloat()
            }
        }
        val side = (resources.displayMetrics.widthPixels * 0.7f).toInt().coerceAtMost(dp(360))
        root.addView(guide, FrameLayout.LayoutParams(side, side, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(120) })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(220, 15, 23, 42))
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "Hold your PisoPhone card up to the camera"
        }
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))

        nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        nameInput = EditText(this).apply {
            hint = "Your name (up to 16 letters or numbers)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(
                InputFilter.LengthFilter(16),
                InputFilter { src, _, _, _, _, _ -> src.filter { it.isLetterOrDigit() && it.code < 128 || it == ' ' || it == '_' || it == '-' } },
            )
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) submitName()
                true
            }
        }
        nameBox.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        panel.addView(nameBox, LinearLayout.LayoutParams(-1, -2))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        primaryButton = Button(this).apply { visibility = View.GONE }
        cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { closeScreen() }
        }
        buttons.addView(cancelButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        buttons.addView(primaryButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun setMode(m: Mode) {
        mode = m
        guide.visibility = if (m == Mode.SCANNING || m == Mode.WORKING) View.VISIBLE else View.INVISIBLE
        nameBox.visibility = if (m == Mode.NAME) View.VISIBLE else View.GONE
        primaryButton.visibility = if (m == Mode.NAME || m == Mode.MESSAGE) View.VISIBLE else View.GONE
        cancelButton.visibility = if (m == Mode.WORKING) View.GONE else View.VISIBLE
        resetTimeout()
    }

    private fun showMessage(text: String, retry: Boolean) {
        status.text = text
        setMode(Mode.MESSAGE)
        primaryButton.text = if (retry) "Scan again" else "Close"
        primaryButton.setOnClickListener { if (retry) scanAgain() else closeScreen() }
    }

    private fun scanAgain() {
        status.text = "Hold your PisoPhone card up to the camera"
        ignoredSince = 0L
        setMode(Mode.SCANNING)
    }

    private fun resetTimeout() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, if (mode == Mode.NAME) 120_000L else 45_000L)
    }

    private fun closeScreen() {
        if (finishing) return
        finishing = true
        finish()
    }

    // ---- card ----

    private fun onCardText(text: String) {
        if (mode != Mode.SCANNING) return
        if (!Esp32AccountRequests.looksLikeCard(text)) {
            // Some other QR code: keep looking, but say so after a moment so the player knows it was seen.
            val now = System.currentTimeMillis()
            if (ignoredSince == 0L) ignoredSince = now
            if (now - ignoredSince > 2500L) status.text = "That is not a PisoPhone card. Hold your card up to the camera."
            return
        }
        status.text = "Checking your card…"
        setMode(Mode.WORKING)
        AccountController.scanCard(text) { reply ->
            if (finishing) return@scanCard
            if (!reply.success) {
                showMessage(Esp32AccountRequests.describe(reply.error), retry = reply.error != "SESSION_ACTIVE" && reply.error != "ALREADY_SIGNED_IN")
                return@scanCard
            }
            val bonusText = if (reply.bonusSec > 0) "Welcome! ${formatDuration(reply.bonusSec)} added to your new account." else ""
            if (reply.name.isBlank()) {
                status.text = (bonusText + "\nChoose a name so you can tell your account apart.").trim()
                setMode(Mode.NAME)
                primaryButton.text = "Save"
                primaryButton.setOnClickListener { submitName() }
                nameInput.requestFocus()
            } else {
                status.text = (bonusText.ifEmpty { "Welcome back, ${reply.name}!" }) + "\n${formatDuration(reply.balanceSec)} on your account."
                setMode(Mode.MESSAGE)
                primaryButton.visibility = View.GONE
                main.postDelayed({ closeScreen() }, 2200L)
            }
        }
    }

    private fun submitName() {
        if (mode != Mode.NAME) return
        val name = nameInput.text.toString().trim()
        if (!Esp32AccountRequests.isValidName(name)) {
            status.text = Esp32AccountRequests.describe("BAD_NAME")
            return
        }
        status.text = "Saving…"
        setMode(Mode.WORKING)
        AccountController.setName(name) { reply ->
            if (finishing) return@setName
            if (reply.success) {
                status.text = "Saved. Enjoy your time, ${reply.name}!"
                setMode(Mode.MESSAGE)
                primaryButton.visibility = View.GONE
                main.postDelayed({ closeScreen() }, 1500L)
            } else {
                status.text = Esp32AccountRequests.describe(reply.error)
                setMode(Mode.NAME)
                primaryButton.text = "Save"
            }
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h hour" + if (h > 1) "s" else ""
            else -> "$m min"
        }
    }

    // ---- camera ----

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor) { image -> analyze(image) } }
                    val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, analysis)
                } catch (e: Exception) {
                    Log.e(TAG, "Camera could not start: ${e.message}", e)
                    showMessage("The camera could not start. Ask the attendant.", retry = false)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (mode != Mode.SCANNING) return
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val data = ByteArray(rowStride * image.height)
            val buffer = plane.buffer
            buffer.get(data, 0, minOf(buffer.remaining(), data.size))
            val source = PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: Exception) {
                null
            } finally {
                reader.reset()
            }
            if (text != null) main.post { onCardText(text) }
        } finally {
            image.close()
        }
    }

    private companion object {
        private const val TAG = "CardScanActivity"
    }
}
