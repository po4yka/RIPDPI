package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogcatSnapshotCollectorTest {
    @Test
    fun `capture returns snapshot when output is non blank`() =
        runTest {
            val output = "03-12 10:00:00.000 I/RIPDPI: diagnostics ready\n"
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(): String = output
                }

            val snapshot = collector.capture()

            requireNotNull(snapshot)
            assertEquals(output, snapshot.content)
            assertEquals(LogcatSnapshotCollector.AppVisibleSnapshotScope, snapshot.captureScope)
            assertEquals(output.toByteArray(Charsets.UTF_8).size, snapshot.byteCount)
        }

    @Test
    fun `capture returns null when output is blank`() =
        runTest {
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(): String = "   "
                }

            assertNull(collector.capture())
        }

    @Test
    fun `capture returns null when logcat command fails`() =
        runTest {
            val collector =
                object : LogcatSnapshotCollector() {
                    override fun readLogcatOutput(): String = error("logcat unavailable")
                }

            assertNull(collector.capture())
        }
}
