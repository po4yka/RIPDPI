package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.pcap.PcapCaptureMetadata
import com.poyka.ripdpi.ui.screens.diagnostics.PcapCaptureListScreen
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RoborazziPcapCaptureListScreenTest {
    @Test
    fun pcapCaptureListEmpty() {
        capturePcapCaptureListScreen(captures = persistentListOf())
    }

    @Test
    fun pcapCaptureListWithItems() {
        capturePcapCaptureListScreen(
            captures =
                persistentListOf(
                    PcapCaptureMetadata(
                        path = "/data/data/com.poyka.ripdpi/files/pcap/0000000000000001-1716640000000-00.pcap",
                        byteSize = 16_384,
                        packetCount = 482,
                        startedAtMs = 1_716_640_000_000L,
                        endedAtMs = 1_716_640_090_000L,
                        drops = 0,
                    ),
                    PcapCaptureMetadata(
                        path = "/data/data/com.poyka.ripdpi/files/pcap/0000000000000001-1716640090000-01.pcap",
                        byteSize = 8_192,
                        packetCount = 240,
                        startedAtMs = 1_716_640_090_000L,
                        endedAtMs = 1_716_640_140_000L,
                        drops = 12,
                    ),
                ),
        )
    }
}

private fun capturePcapCaptureListScreen(captures: ImmutableList<PcapCaptureMetadata>) {
    captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
        RipDpiTheme(themePreference = "light") {
            PcapCaptureListScreen(
                captures = captures,
                onCaptureSelected = {},
            )
        }
    }
}
