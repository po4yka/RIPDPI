package com.poyka.ripdpi.pcap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class PcapControllerMetadataTest {
    @Test
    fun `empty native list is distinct from native failure`() {
        assertEquals(emptyList<PcapCaptureMetadata>(), PcapController.decodeMetadataList("[]"))
        assertThrows(IOException::class.java) { PcapController.decodeMetadataList(null) }
        assertThrows(IOException::class.java) { PcapController.decodeMetadataList("not json") }
    }
}
