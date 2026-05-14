package com.poyka.ripdpi.ui.screens.scanner

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * CameraX [ImageAnalysis.Analyzer] that runs ML Kit barcode detection on each frame and
 * reports the first decoded QR payload.
 *
 * The scanner is configured for QR codes only to keep per-frame work small. [onQrDecoded]
 * fires once per decoded payload; the screen is responsible for debouncing repeat
 * detections of the same code while it routes the result.
 */
class QrCameraAnalyzer(
    private val onQrDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner: BarcodeScanner =
        BarcodeScanning.getClient(
            BarcodeScannerOptions
                .Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val inputImage =
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner
            .process(inputImage)
            .addOnSuccessListener { barcodes ->
                barcodes
                    .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    ?.rawValue
                    ?.let(onQrDecoded)
            }.addOnCompleteListener {
                imageProxy.close()
            }
    }
}
