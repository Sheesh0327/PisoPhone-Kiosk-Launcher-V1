package com.pisophone.kiosk.ui.video

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.pisophone.kiosk.security.KioskSecurity

/**
 * A muted, looping video behind other content, with its first frame shown as a still while the video is not playing (and
 * always on phones that cannot afford the decoder, see [VideoBackgroundPolicy]). The video exists only while it plays: when it
 * should not, no player and no decoder are alive, not even paused.
 *
 * It plays while the screen it belongs to is visible (the lifecycle of the activity or overlay it sits in is started) and
 * nothing covers it ([covered]).
 */
@Composable
fun LoopingVideoBackground(
    @RawRes videoRes: Int,
    @DrawableRes posterRes: Int,
    modifier: Modifier = Modifier,
    covered: Boolean = false,
) {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    var inFront by remember { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> inFront = true
                Lifecycle.Event.ON_STOP -> inFront = false
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    // read again each time the screen comes back: the battery and the admin's switch may have changed
    val play = remember(inFront, covered) {
        val facts = VideoBackgroundPolicy.readDeviceFacts(context)
        VideoBackgroundPolicy.shouldPlay(
            enabled = KioskSecurity.isVideoBackgroundEnabled(context),
            lowRamDevice = facts.lowRamDevice,
            powerSaveMode = facts.powerSaveMode,
            batteryPercent = facts.batteryPercent,
            charging = facts.charging,
            screenInFront = inFront,
            covered = covered,
        )
    }

    Box(modifier) {
        Image(
            painter = painterResource(posterRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (play) {
            var failed by remember { mutableStateOf(false) }
            var rendered by remember { mutableStateOf(false) }
            val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(400), label = "videoFade")
            if (!failed) {
                AndroidView(
                    factory = { ctx ->
                        LoopingVideoView(ctx, videoRes).apply {
                            onFirstFrame = { rendered = true }
                            onFailed = { failed = true }
                        }
                    },
                    modifier = Modifier.fillMaxSize().alpha(alpha),
                )
            }
        }
    }
}

/**
 * Plays one raw video resource in a loop, silently, filling the view (cropping the overflow). Creates its player when the
 * surface exists and releases it as soon as the surface or the view goes away.
 */
class LoopingVideoView(context: Context, @RawRes private val rawRes: Int) : TextureView(context), TextureView.SurfaceTextureListener {
    var onFirstFrame: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = start(texture)

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = applyCropTransform()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun start(texture: SurfaceTexture) {
        release()
        try {
            val mp = MediaPlayer()
            resources.openRawResourceFd(rawRes).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val s = Surface(texture)
            surface = s
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                applyCropTransform()
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame?.invoke()
                false
            }
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Video background failed (what=$what, extra=$extra): showing the still image instead.")
                release()
                onFailed?.invoke()
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Video background could not start: ${e.message}")
            release()
            onFailed?.invoke()
        }
    }

    /** A TextureView stretches the video to the view; this scales it back to its own shape, centered, overflow cropped. */
    private fun applyCropTransform() {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
        val (sx, sy) = VideoBackgroundPolicy.cropScale(width.toFloat(), height.toFloat(), videoWidth.toFloat(), videoHeight.toFloat())
        setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
    }

    fun release() {
        player?.let {
            try {
                it.setOnErrorListener(null)
                it.setOnInfoListener(null)
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        surface?.release()
        surface = null
    }

    companion object {
        private const val TAG = "LoopingVideoView"
    }
}
