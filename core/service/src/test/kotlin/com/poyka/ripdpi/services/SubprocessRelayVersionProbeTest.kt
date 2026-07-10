package com.poyka.ripdpi.services

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SubprocessRelayVersionProbeTest {
    @Test
    fun `probe timeout is enforced before reading output to eof`() {
        val started = TimeSource.Monotonic.markNow()
        val result =
            SubprocessRelayVersionProbe().probe(
                binary = File("/bin/sh"),
                spec =
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = "sh",
                        commandArguments = emptyList(),
                        versionArguments = listOf("-c", "sleep 10"),
                        runtimeKind = "test",
                    ),
            )

        assertNull(result)
        assertTrue("version probe exceeded its timeout", started.elapsedNow() < 5.seconds)
    }
}
