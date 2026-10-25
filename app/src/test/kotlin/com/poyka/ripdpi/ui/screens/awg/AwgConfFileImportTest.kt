package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AwgConfFileImportTest {
    @Test
    fun `utf-8 byte-order mark is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[Interface]".toByteArray()

        assertEquals("[Interface]", decodeConfText(bytes))
    }

    @Test
    fun `binary file is rejected`() {
        assertNull(decodeConfText(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0xFF.toByte())))
    }

    @Test
    fun `oversized file is rejected`() {
        assertNull(decodeConfText(ByteArray(PendingProxyImportStore.MaxAmneziaWgConfBytes + 1) { 'a'.code.toByte() }))
    }
}
