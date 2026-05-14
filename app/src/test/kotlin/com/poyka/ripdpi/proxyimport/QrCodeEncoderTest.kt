package com.poyka.ripdpi.proxyimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [QrCodeEncoder]: offline QR-matrix generation via `zxing-core`.
 *
 * Encoding is fully offline — no Play Services, no network. The encoder produces a square
 * bit-matrix; turning that matrix into an Android `Bitmap` is the screen's job and is not
 * exercised here (it needs the Android graphics stack).
 */
class QrCodeEncoderTest {
    @Test
    fun `encoding a uri produces a square matrix at the requested size`() {
        val matrix = QrCodeEncoder.encodeMatrix("vless://uuid@example.com:443#Node", size = 256)

        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
    }

    @Test
    fun `the encoded matrix has both set and unset modules`() {
        val matrix = QrCodeEncoder.encodeMatrix("ss://abc@example.com:8388#Node", size = 128)

        var anySet = false
        var anyUnset = false
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) anySet = true else anyUnset = true
            }
        }
        assertTrue(anySet)
        assertTrue(anyUnset)
    }

    @Test
    fun `the same content encodes deterministically`() {
        val first = QrCodeEncoder.encodeMatrix("trojan://pass@example.com:443#Node", size = 200)
        val second = QrCodeEncoder.encodeMatrix("trojan://pass@example.com:443#Node", size = 200)

        for (x in 0 until first.width) {
            for (y in 0 until first.height) {
                assertEquals(first[x, y], second[x, y])
            }
        }
    }

    @Test
    fun `blank content cannot be encoded and yields null`() {
        assertNull(QrCodeEncoder.encodeMatrixOrNull("", size = 128))
        assertNull(QrCodeEncoder.encodeMatrixOrNull("   ", size = 128))
    }

    @Test
    fun `a non-positive size yields null rather than throwing`() {
        assertNull(QrCodeEncoder.encodeMatrixOrNull("vless://uuid@example.com:443", size = 0))
        assertNull(QrCodeEncoder.encodeMatrixOrNull("vless://uuid@example.com:443", size = -10))
    }
}
