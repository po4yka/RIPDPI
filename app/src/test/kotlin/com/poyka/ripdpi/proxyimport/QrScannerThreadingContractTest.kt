package com.poyka.ripdpi.proxyimport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QrScannerThreadingContractTest {
    @Test
    fun `camera provider is not awaited synchronously from compose`() {
        val source =
            File(
                "src/main/kotlin/com/poyka/ripdpi/ui/screens/scanner/QrScannerScreen.kt",
            ).readText()

        assertFalse(
            "Camera provider initialization must not block the Compose main thread",
            source.contains("ProcessCameraProvider.getInstance(context).get()"),
        )
        assertTrue(
            "Camera provider must be observed asynchronously",
            source.contains("cameraProviderFuture.addListener"),
        )
        assertTrue(
            "Disposed scanner must reject late camera binding",
            source.contains("if (disposed) return@addListener"),
        )
    }

    @Test
    fun `picked image is decoded off the main thread`() {
        val source =
            File(
                "src/main/kotlin/com/poyka/ripdpi/ui/screens/scanner/QrImageScanner.kt",
            ).readText()

        assertTrue(
            "Image loading must use the IO dispatcher",
            source.contains("withContext(Dispatchers.IO)"),
        )
    }
}
