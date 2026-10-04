package com.deskbuddy.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.media.FaceDetector
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Whether someone is in front of the desk, and roughly where (x, y in -1..1, as Desku sees them). */
data class Presence(val present: Boolean = false, val x: Float = 0f, val y: Float = 0f, val seenAt: Long = 0L)

/**
 * The camera, always on and never shown. Face detection runs on the phone about once a second
 * (Android's built-in FaceDetector, no network), so Desku knows when you're there and where to
 * look. Frames are analysed in memory and dropped; a photo is only taken, and only sent, when
 * Desku needs to look at something. Bound to the activity's lifecycle, so it stops whenever the
 * app isn't on screen, and Android shows its green camera dot the whole time it runs.
 */
class AlwaysOnEyes(private val context: Context) {

    private val _presence = MutableStateFlow(Presence())
    val presence: StateFlow<Presence> = _presence.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var lastAnalysis = 0L
    private var front = true

    /** Main thread. Safe to call again; rebinds if the lens changed. */
    fun start(owner: LifecycleOwner, frontCamera: Boolean) {
        front = frontCamera
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val selector = when {
                frontCamera && p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> CameraSelector.DEFAULT_FRONT_CAMERA
            }
            front = selector == CameraSelector.DEFAULT_FRONT_CAMERA
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            val still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, selector, analysis, still)
                capture = still
                _running.value = true
            } catch (e: Exception) {
                Log.e(TAG, "couldn't start the always-on camera", e)
                capture = null
                _running.value = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Main thread. */
    fun stop() {
        runCatching { provider?.unbindAll() }
        capture = null
        _running.value = false
        _presence.value = Presence()
    }

    /** One photo, right now, no countdown. Null if the camera isn't running or the capture failed. */
    suspend fun snapshot(): ByteArray? {
        val c = capture ?: return null
        return suspendCancellableCoroutine { cont ->
            c.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val jpeg = runCatching { toJpeg(image) }.getOrNull()
                    image.close()
                    if (cont.isActive) cont.resume(jpeg)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "snapshot failed", exception)
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    private val detector by lazy { FaceDetector(DETECT_W, DETECT_H, 1) }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysis < ANALYSIS_INTERVAL_MS) {
            image.close()
            return
        }
        lastAnalysis = now
        try {
            val found = findFace(image)
            val prev = _presence.value
            _presence.value = when {
                found != null -> {
                    // Smooth so the eyes glide instead of jumping.
                    val x = if (prev.present) prev.x * .5f + found.x * .5f else found.x
                    val y = if (prev.present) prev.y * .5f + found.y * .5f else found.y
                    Presence(true, x, y, System.currentTimeMillis())
                }
                // Keep "present" a little while after losing the face (a glance away isn't leaving).
                prev.present && System.currentTimeMillis() - prev.seenAt < LOST_AFTER_MS -> prev
                else -> Presence(false, 0f, 0f, prev.seenAt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "analysis failed", e)
        } finally {
            image.close()
        }
    }

    private fun findFace(image: ImageProxy): PointF? {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        // Upright, mirrored for the selfie camera (so "left" means your left on the screen),
        // squeezed into the detector's fixed frame. FaceDetector wants RGB_565 and an even width.
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply {
            postRotate(rotation.toFloat())
            if (front) postScale(-1f, 1f)
        }, false)
        val small = Bitmap.createScaledBitmap(upright, DETECT_W, DETECT_H, true).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<FaceDetector.Face>(1)
        val n = detector.findFaces(small, faces)
        val face = faces[0]
        if (n < 1 || face == null || face.confidence() < .3f) return null
        val mid = PointF()
        face.getMidPoint(mid)
        return PointF(mid.x / DETECT_W * 2f - 1f, mid.y / DETECT_H * 2f - 1f)
    }

    fun shutdown() {
        stop()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "AlwaysOnEyes"
        private const val ANALYSIS_INTERVAL_MS = 900L
        private const val LOST_AFTER_MS = 4_000L
        // Portrait-shaped frame; plenty for a face at desk distance and cheap on old phones.
        private const val DETECT_W = 240
        private const val DETECT_H = 320
    }
}
