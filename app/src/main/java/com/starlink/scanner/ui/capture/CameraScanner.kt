package com.starlink.scanner.ui.capture

import androidx.camera.core.CameraSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starlink.scanner.domain.BarcodeFormat
import java.util.concurrent.Executors

/**
 * Full-bleed CameraX preview with ML Kit barcode analysis (Module 3). Hosts a [PreviewView] in an
 * [AndroidView] and drives it with a [LifecycleCameraController], so binding/unbinding follows the
 * composition lifecycle automatically. Decoded raw values are pushed up through [onBarcode].
 *
 * Caller is responsible for having the CAMERA permission granted before this composable enters the
 * tree.
 */
@Composable
fun CameraScanner(
    onBarcode: (String, BarcodeFormat) -> Unit,
    torchEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keep the latest lambda without rebuilding the analyzer/controller on recomposition.
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisAnalyzer(
                analysisExecutor,
                BarcodeAnalyzer { raw, format -> currentOnBarcode(raw, format) },
            )
        }
    }

    // Torch follows UI state; the controller ignores it until a camera is bound, hence the effect.
    LaunchedEffect(torchEnabled) { controller.enableTorch(torchEnabled) }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.controller = controller
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // TextureView (not the default SurfaceView): a SurfaceView renders on a separate
                // hardware layer that doesn't clip to these Compose bounds, so its lower edge bled
                // over the fields below. A TextureView composes normally and stays within its pane.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
    )
}
