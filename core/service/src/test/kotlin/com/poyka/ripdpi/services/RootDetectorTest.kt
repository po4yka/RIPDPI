package com.poyka.ripdpi.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class RootDetectorTest {
    @Test
    fun `grants root when magisk su form succeeds`() =
        runTest {
            val detector =
                RootDetector(
                    FakeRootCommandRunner(
                        mapOf(
                            listOf("su", "-c", "id") to RootCommandResult(0, "uid=0(root) gid=0(root)"),
                        ),
                    ),
                )

            assertEquals(RootAccessResult.Granted, detector.testRootAccess())
        }

    @Test
    fun `tries aosp su form when magisk su form is denied`() =
        runTest {
            val runner =
                FakeRootCommandRunner(
                    mapOf(
                        listOf("su", "-c", "id") to RootCommandResult(1, "su: invalid uid/gid '-c'"),
                        listOf("su", "0", "id") to RootCommandResult(0, "uid=0(root) gid=0(root)"),
                    ),
                )
            val detector = RootDetector(runner)

            assertEquals(RootAccessResult.Granted, detector.testRootAccess())
            assertEquals(
                listOf(
                    listOf("su", "-c", "id"),
                    listOf("su", "0", "id"),
                ),
                runner.commands,
            )
        }

    @Test
    fun `denies root when all available su forms fail`() =
        runTest {
            val detector =
                RootDetector(
                    FakeRootCommandRunner(
                        mapOf(
                            listOf("su", "-c", "id") to RootCommandResult(1, "permission denied"),
                            listOf("su", "0", "id") to RootCommandResult(1, "permission denied"),
                        ),
                    ),
                )

            assertEquals(RootAccessResult.Denied, detector.testRootAccess())
        }

    @Test
    fun `reports unavailable when no su command can run`() =
        runTest {
            val detector = RootDetector(FakeRootCommandRunner(emptyMap()))

            assertEquals(RootAccessResult.Unavailable, detector.testRootAccess())
        }

    private class FakeRootCommandRunner(
        private val results: Map<List<String>, RootCommandResult>,
    ) : RootCommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>): RootCommandResult {
            commands += command
            return results[command] ?: throw IOException("missing command")
        }
    }
}
