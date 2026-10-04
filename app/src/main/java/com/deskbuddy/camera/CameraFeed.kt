package com.deskbuddy.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deskbuddy.brain.LookResult
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/** One request to look: the overlay completes it with a photo or a reason there isn't one. */
class LookRequest(val reason: String) {
    val result = kotlinx.coroutines.CompletableDeferred<LookResult>()
}

/**
 * The camera is only ever on while this is on screen: a live preview so the user can aim, a
 * short countdown ([onCount] gets 3, 2, 1, 0), one photo, then the camera is unbound again.
 */
@Composable
fun CameraFeed(request: LookRequest, frontCamera: Boolean, onCount: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            cameraSelector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    DisposableEffect(controller) {
        controller.bindToLifecycle(lifecycleOwner)
        // Fall back to whichever camera the phone has.
        controller.initializationFuture.addListener({
            val wanted = controller.cameraSelector
            if (!runCatching { controller.hasCamera(wanted) }.getOrDefault(true)) {
                controller.cameraSelector =
                    if (wanted == CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { controller.unbind() }
    }

    LaunchedEffect(request) {
        var count = COUNTDOWN
        while (count > 0) {
            onCount(count)
            delay(1_000)
            count--
        }
        onCount(0)
        controller.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val jpeg = runCatching { toJpeg(image) }.onFailure { Log.e("CameraFeed", "encode failed", it) }.getOrNull()
                    image.close()
                    request.result.complete(if (jpeg != null) LookResult.Photo(jpeg) else LookResult.Unavailable("the photo couldn't be processed"))
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraFeed", "capture failed", exception)
                    request.result.complete(LookResult.Unavailable("the camera failed to take a photo"))
                }
            },
        )
    }

    AndroidView(
        factory = { ctx -> PreviewView(ctx).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
        modifier = modifier,
    )
}

private const val COUNTDOWN = 3
private const val MAX_EDGE = 1280

/** Upright, at most 1280 px on the long edge, JPEG ~80: plenty to read handwriting, small to send. */
fun toJpeg(image: ImageProxy): ByteArray {
    val raw = image.toBitmap()
    val rotation = image.imageInfo.rotationDegrees
    val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(raw.width, raw.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation.toFloat())
    }
    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    return ByteArrayOutputStream().use { out ->
        upright.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }
}
