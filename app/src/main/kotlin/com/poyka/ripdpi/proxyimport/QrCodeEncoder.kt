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
    private const val MaximumContentLength = 5596 // Version 40, level M, numeric mode.
    private const val MaximumMatrixSize = 2048

    private val writer = QRCodeWriter()

    private val hints =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )

    /**
     * Encodes [content] into a square [BitMatrix] of [size] by [size] pixels. Throws
     * [IllegalArgumentException] if [encodeMatrixOrNull] cannot make a matrix.
     */
    fun encodeMatrix(
        content: String,
        size: Int,
    ): BitMatrix =
        encodeMatrixOrNull(content, size)
            ?: throw IllegalArgumentException("Content cannot fit in the requested QR matrix")

    /**
     * Encodes [content] into a square [BitMatrix], or returns `null` when [content] is
     * blank, malformed, too long, or does not fit the requested matrix size.
     */
    fun encodeMatrixOrNull(
        content: String,
        size: Int,
    ): BitMatrix? {
        if (size !in 1..MaximumMatrixSize || content.length > MaximumContentLength || content.isBlank()) return null
        return content
            .takeIf(Charsets.UTF_8.newEncoder()::canEncode)
            ?.let { value ->
                runCatching { writer.encode(value, BarcodeFormat.QR_CODE, size, size, hints) }.getOrNull()
            }?.takeIf { it.width == size && it.height == size }
    }
}
