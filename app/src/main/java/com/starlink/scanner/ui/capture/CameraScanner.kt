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
import com.starlink.scanner.domain.ScanMode
import java.util.concurrent.Executors

/**
 * Full-bleed CameraX preview with ML Kit analysis (Module 3). Hosts a [PreviewView] in an
 * [AndroidView] and drives it with a [LifecycleCameraController], so binding/unbinding follows the
 * composition lifecycle automatically.
 *
 * [mode] selects which model runs on the frames: [ScanMode.BARCODE] decodes symbologies and reports
 * through [onBarcode], [ScanMode.TEXT] reads printed text and reports through [onText]. Only one
 * runs at a time — both on every frame would double the per-frame cost for a value only one of them
 * can supply.
 *
 * Caller is responsible for having the CAMERA permission granted before this composable enters the
 * tree.
 */
@Composable
fun CameraScanner(
    mode: ScanMode,
    onBarcode: (String, BarcodeFormat) -> Unit,
    onText: (String) -> Unit,
    torchEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keep the latest lambdas without rebuilding the analyzer/controller on recomposition.
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentOnText by rememberUpdatedState(onText)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }

    // Swap the analyzer when the technician switches modes. Each ML Kit client owns a loaded model,
    // so the outgoing one is closed rather than left to the garbage collector.
    DisposableEffect(mode) {
        val analyzer: FrameAnalyzer = when (mode) {
            ScanMode.BARCODE -> BarcodeAnalyzer { raw, format -> currentOnBarcode(raw, format) }
            ScanMode.TEXT -> TextAnalyzer { text -> currentOnText(text) }
        }
        controller.setImageAnalysisAnalyzer(analysisExecutor, analyzer)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            analyzer.close()
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
