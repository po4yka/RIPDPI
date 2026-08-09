package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCaptureDisposition
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCaptureManifestPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

internal class DiagnosticsArchiveCaptureManifestTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `createArchive inventories separate packet capture with scoped evidence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            seedCompositeSessionStores(stores)
            val outcome = buildSampleCompositeOutcome()
            compositeRunService.putCompletedRun(outcome)
            val context = TestContext()
            val capture = writePcapFixture(context)
            writePcapFixture(context, "0000000000000002-12-00.pcap")
            context.cacheDir
                .resolve("diagnostics/0000000000000002-13-00.pcap.active")
                .writeBytes(byteArrayOf(1))

            val archive =
                createArchiveExporter(stores, context, rootModeEnabled = false).createArchive(
                    DiagnosticsArchiveRequest(
                        sessionIds = outcome.bundleSessionIds,
                        homeRunId = outcome.runId,
                        reason = DiagnosticsArchiveReason.SHARE_HOME_ANALYSIS,
                        requestedAt = 29L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                assertFalse(zip.entries().asSequence().any { it.name.endsWith(".pcap") })
                val captureManifest =
                    json.decodeFromString(
                        DiagnosticsArchiveCaptureManifestPayload.serializer(),
                        zip.getInputStream(zip.getEntry("capture-manifest.json")).bufferedReader().readText(),
                    )
                assertEquals("capture_manifest_v1", captureManifest.captureManifestVersion)
                assertEquals(13, captureManifest.archiveSchemaVersion)
                assertFalse(captureManifest.rawArtifactsEmbedded)
                assertEquals(DiagnosticsArchiveCaptureDisposition.CAPTURED, captureManifest.discoveryDisposition)
                val entry = captureManifest.captures.single()
                assertEquals("capture-1", entry.captureId)
                assertEquals(capture.readBytes().sha256(), entry.sha256)
                assertEquals(12L, entry.captureWindow.startedAt)
                assertEquals(18L, entry.captureWindow.finishedAt)
                assertEquals(2L, entry.packetCount)
                assertEquals(6L, entry.capturedPacketBytes)
                assertEquals(8L, entry.originalPacketBytes)
                assertEquals(62L, entry.fileByteCount)
                assertEquals(65_535L, entry.snaplen)
                assertEquals(101L, entry.linkType)
                assertTrue(entry.truncated)
                assertTrue("automatic_audit" in entry.stageIds)
                assertTrue(entry.attemptIds.isEmpty())
                assertEquals("attempt_timestamps_unavailable", entry.associationWarnings.single())
                assertFalse(entry.rawFileNamePresent)
            }
        }

    @Test
    fun `createArchive records invalid capture without exposing its file name`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-invalid-capture",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Invalid capture fixture",
                )
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            val rawFileName = "sensitive-capture-name.pcap"
            context.cacheDir
                .resolve("diagnostics")
                .apply { mkdirs() }
                .resolve(rawFileName)
                .writeBytes(byteArrayOf(1, 2))

            val archive =
                createArchiveExporter(stores, context, rootModeEnabled = false).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 29L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val text = zip.getInputStream(zip.getEntry("capture-manifest.json")).bufferedReader().readText()
                val captureManifest =
                    json.decodeFromString(DiagnosticsArchiveCaptureManifestPayload.serializer(), text)
                val entry = captureManifest.captures.single()
                assertEquals(DiagnosticsArchiveCaptureDisposition.INVALID, entry.disposition)
                assertEquals("pcap_global_header_incomplete", entry.parseFailureReason)
                assertNotNull(entry.sha256)
                assertFalse(entry.rawFileNamePresent)
                assertFalse(text.contains(rawFileName))
            }
        }

    private fun writePcapFixture(
        context: TestContext,
        fileName: String = "0000000000000001-12-00.pcap",
    ): java.io.File {
        val pcapDir = context.cacheDir.resolve("diagnostics").apply { mkdirs() }
        return pcapDir.resolve(fileName).apply {
            val bytes = ByteBuffer.allocate(62).order(ByteOrder.LITTLE_ENDIAN)
            bytes.putInt(0xa1b2c3d4.toInt())
            bytes.putShort(2)
            bytes.putShort(4)
            bytes.putInt(0)
            bytes.putInt(0)
            bytes.putInt(65_535)
            bytes.putInt(101)
            bytes.putInt(0)
            bytes.putInt(12_000)
            bytes.putInt(3)
            bytes.putInt(4)
            bytes.put(byteArrayOf(1, 2, 3))
            bytes.putInt(0)
            bytes.putInt(18_000)
            bytes.putInt(3)
            bytes.putInt(4)
            bytes.put(byteArrayOf(4, 5, 6))
            writeBytes(bytes.array())
        }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
}
