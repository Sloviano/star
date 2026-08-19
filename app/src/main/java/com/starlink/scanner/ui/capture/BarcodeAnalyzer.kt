package com.starlink.scanner.ui.capture

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.starlink.scanner.domain.BarcodeFormat

/**
 * CameraX [ImageAnalysis.Analyzer] that runs ML Kit barcode detection on each frame and reports
 * every decoded raw value plus its symbology via [onBarcode]. Duplicate-scan debouncing and
 * classification happen downstream in the ViewModel — this stays a near-dumb pipe (it only maps
 * ML Kit's format ints to the domain [BarcodeFormat] so ML Kit types stay out of the ViewModel).
 *
 * Formats are restricted to the ones the kit box uses (Code 128 / QR / Data Matrix) for speed.
 */
class BarcodeAnalyzer(
    private val onBarcode: (String, BarcodeFormat) -> Unit,
) : FrameAnalyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
            )
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.forEach { barcode ->
                    barcode.rawValue?.let { onBarcode(it, barcode.format.toDomainFormat()) }
                }
            }
            // Always close the frame so the pipeline can deliver the next one.
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Release the ML Kit model. The controller drops this analyzer whenever the mode changes. */
    override fun close() = scanner.close()
}

/** Map an ML Kit [Barcode] format constant to the domain [BarcodeFormat]. */
private fun Int.toDomainFormat(): BarcodeFormat = when (this) {
    Barcode.FORMAT_DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
    Barcode.FORMAT_CODE_128 -> BarcodeFormat.CODE_128
    Barcode.FORMAT_QR_CODE -> BarcodeFormat.QR
    else -> BarcodeFormat.OTHER
}
