package com.poyka.ripdpi.ui.screens.scanner

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Decodes a QR code from a still image picked through the Storage Access Framework.
 *
 * This is the camera-free fallback for the QR scanner screen: when the camera permission
 * is denied, the user can still pick a saved QR image and have it decoded here via the
 * same ML Kit barcode scanner the live CameraX path uses. Returns the first decoded QR
 * payload, or `null` when the image holds no readable QR.
 */
object QrImageScanner {
    private val scanner by lazy { BarcodeScanning.getClient() }

    /**
     * Reads [uri] into an [InputImage] and runs ML Kit barcode detection. Returns the raw
     * value of the first QR-format barcode found, or `null` when none is present or the
     * image cannot be read.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
    ): String? {
        val image =
            withContext(Dispatchers.IO) {
                runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
            } ?: return null
        return suspendCancellableCoroutine { continuation ->
            scanner
                .process(image)
                .addOnSuccessListener { barcodes ->
                    val payload =
                        barcodes
                            .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                            ?.rawValue
                    continuation.resume(payload)
                }.addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }
}
