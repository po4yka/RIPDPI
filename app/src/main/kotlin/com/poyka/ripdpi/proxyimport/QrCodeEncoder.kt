package com.poyka.ripdpi.proxyimport

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Offline QR-matrix generator backed by `zxing-core`.
 *
 * Encoding is fully local — no Play Services, no network round-trip. The encoder returns
 * a square [BitMatrix]; converting that matrix into an Android `Bitmap` is the caller's
 * job (it needs the Android graphics stack, so it stays out of this pure-logic unit).
 */
object QrCodeEncoder {
    private val writer = QRCodeWriter()

    private val hints =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )

    /**
     * Encodes [content] into a square [BitMatrix] of [size] x [size] modules. Throws
     * [IllegalArgumentException] for blank content or a non-positive [size]; callers that
     * want a soft failure should use [encodeMatrixOrNull].
     */
    fun encodeMatrix(
        content: String,
        size: Int,
    ): BitMatrix =
        encodeMatrixOrNull(content, size)
            ?: throw IllegalArgumentException("Cannot encode blank content or a non-positive size")

    /**
     * Encodes [content] into a square [BitMatrix], or returns `null` when [content] is
     * blank, [size] is non-positive, or `zxing-core` rejects the input.
     */
    fun encodeMatrixOrNull(
        content: String,
        size: Int,
    ): BitMatrix? {
        if (content.isBlank() || size <= 0) return null
        return runCatching {
            writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        }.getOrNull()
    }
}
