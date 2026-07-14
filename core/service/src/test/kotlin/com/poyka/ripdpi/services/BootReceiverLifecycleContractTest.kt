package com.poyka.ripdpi.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BootReceiverLifecycleContractTest {
    @Test
    fun `boot receiver keeps foreground service start inside broadcast lifetime`() {
        val source = sourceFile("src/main/kotlin/com/poyka/ripdpi/services/BootReceiver.kt").readText()

        assertTrue("BootReceiver must retain the broadcast with goAsync()", source.contains("goAsync()"))
        assertTrue("BootReceiver must finish its pending result", source.contains("pendingResult::finish"))
        assertFalse(
            "WorkManager runs after the boot FGS exemption and must not own boot resume",
            source.contains("BootResumeWorker.enqueue"),
        )
    }

    @Test
    fun `boot resume releases broadcast when deadline expires`() =
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            var finishCount = 0
            val failures = mutableListOf<Throwable>()

            val job =
                launchBoundedBootResume(
                    scope = this,
                    timeoutMillis = 1_000L,
                    resume = { neverCompletes.await() },
                    onFailure = failures::add,
                    finish = { finishCount += 1 },
                )
            advanceTimeBy(1_000L)
            runCurrent()
            job.join()

            assertEquals(1, finishCount)
            assertEquals(1, failures.size)
        }

    @Test
    fun `boot resume releases broadcast once when scope is already cancelled`() =
        runTest {
            val parent = SupervisorJob().also { it.cancel() }
            val scope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
            var finishCount = 0

            val job =
                launchBoundedBootResume(
                    scope = scope,
                    timeoutMillis = 1_000L,
                    resume = {},
                    onFailure = {},
                    finish = { finishCount += 1 },
                )
            runCurrent()
            job.join()

            assertEquals(1, finishCount)
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
