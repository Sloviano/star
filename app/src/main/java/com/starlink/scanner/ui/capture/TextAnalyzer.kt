package com.starlink.scanner.ui.capture

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * CameraX [ImageAnalysis.Analyzer] that runs ML Kit text recognition on each frame and reports
 * everything it read via [onText] (`ScanMode.TEXT`, kit field only).
 *
 * The counterpart to [BarcodeAnalyzer], and deliberately just as dumb a pipe: it does no filtering
 * whatsoever. Deciding which of the words on a kit box is the kit number is
 * [com.starlink.scanner.domain.KitNumber]'s job, where it is a pure function and can be tested
 * against real values instead of against a camera.
 *
 * One frame's text arrives as a single string with newlines between blocks, which is what
 * `KitNumber.extract` expects — it rejoins a code OCR split across lines.
 */
class TextAnalyzer(private val onText: (String) -> Unit) : FrameAnalyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isNotBlank()) onText(visionText.text)
            }
            // Always close the frame so the pipeline can deliver the next one.
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Release the ML Kit model. The controller drops this analyzer whenever the mode changes. */
    override fun close() = recognizer.close()
}
