package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

internal class DiagnosticsArchiveAppLogFailureTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `createArchive records categorical app log read failure`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-app-log-failure",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "App log failure",
                )
            seedSingleSessionStore(stores, session)
            val context = TestContext()
            val fileLogRoot = Files.createTempDirectory("invalid-file-log-test").toFile()
            val invalidLogPath = File(fileLogRoot, "logs/app_log.txt")
            check(invalidLogPath.mkdirs())
            File(invalidLogPath, "private-canary").writeText("secret-app-log.example")
            val exporter =
                createArchiveExporterForTest(
                    stores = stores,
                    context = context,
                    rootModeEnabled = false,
                    compositeRunService = compositeRunService,
                    json = json,
                    fileLogWriter = FileLogWriter(fileLogRoot),
                )

            val archive =
                exporter.createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 106L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val content =
                    zip
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .joinToString("\n") { entry ->
                            zip.getInputStream(entry).bufferedReader().readText()
                        }
                assertTrue(content.contains("app_log_capture_failed:"))
                assertFalse(content.contains("secret-app-log.example"))
                assertFalse(content.contains(fileLogRoot.absolutePath))
            }
        }
}
