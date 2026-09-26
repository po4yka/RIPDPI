package com.poyka.ripdpi.assets

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class GeoAssetDownloadServiceTest {
    @Test
    fun downloadStopsAtSizeLimitEvenWithoutContentLength() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        assertArrayEquals(bytes, readBoundedGeoAssetBytes(ByteArrayInputStream(bytes), maxBytes = 5))
        assertThrows(IOException::class.java) {
            readBoundedGeoAssetBytes(ByteArrayInputStream(bytes), maxBytes = 4)
        }
    }
}
