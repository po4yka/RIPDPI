package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootReceiverLifecycleContractTest {
    @Test
    fun `boot receiver keeps foreground service start inside broadcast lifetime`() {
        val source = sourceFile("src/main/kotlin/com/poyka/ripdpi/services/BootReceiver.kt").readText()

        assertTrue("BootReceiver must retain the broadcast with goAsync()", source.contains("goAsync()"))
        assertTrue("BootReceiver must always finish its pending result", source.contains("pendingResult.finish()"))
        assertFalse(
            "WorkManager runs after the boot FGS exemption and must not own boot resume",
            source.contains("BootResumeWorker.enqueue"),
        )
    }

    @Test
    fun `boot receiver waits for credential storage instead of claiming direct boot`() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("android:directBootAware=\"true\""))
        assertFalse(manifest.contains("android.intent.action.LOCKED_BOOT_COMPLETED"))
    }

    private fun sourceFile(moduleRelativePath: String): File =
        listOf(
            File(moduleRelativePath),
            File("core/service/$moduleRelativePath"),
        ).first { file -> file.exists() }
}
