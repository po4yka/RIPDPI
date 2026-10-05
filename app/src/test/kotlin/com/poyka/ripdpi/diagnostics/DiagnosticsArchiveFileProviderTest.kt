package com.poyka.ripdpi.diagnostics

import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsArchiveFileProviderTest {
    @Test fun `manifest restricts grants and delayed URI reads remain readonly after provider recreation`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val info =
                context.packageManager.getProviderInfo(
                    ComponentName(context, DiagnosticsArchiveFileProvider::class.java),
                    0,
                )
            assertFalse(info.exported)
            assertTrue(info.grantUriPermissions)
            val fixture = prepared(context)
            val first =
                DiagnosticsArchiveFileProvider().apply {
                    attachInfo(context, info)
                    onCreate()
                }
            val uri = FileProvider.getUriForFile(context, info.authority, fixture)
            assertTrue(uri.pathSegments.first() == "diagnostics_prepared")
            withContext(Dispatchers.IO) {
                first.openFile(uri, "r").use { descriptor ->
                    assertArrayEquals(
                        fixture.readBytes(),
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes(),
                    )
                }
                listOf("w", "rw", "wa", "rwt").forEach { mode -> rejected { first.openFile(uri, mode) } }
            }
            val recreated =
                DiagnosticsArchiveFileProvider().apply {
                    attachInfo(context, info)
                    onCreate()
                }
            withContext(Dispatchers.IO) {
                recreated.openFile(uri, "r").close()
                rejected { recreated.openFile(uri.buildUpon().appendPath("foreign").build(), "r") }
                fixture.appendText("tampered")
                rejected { recreated.openFile(uri, "r") }
            }
        }

    @Test fun `expired unconfirmed and backward clock URI reads fail while detection CSV still reads`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val info =
                context.packageManager.getProviderInfo(
                    ComponentName(context, DiagnosticsArchiveFileProvider::class.java),
                    0,
                )
            val provider =
                DiagnosticsArchiveFileProvider().apply {
                    attachInfo(context, info)
                    onCreate()
                }
            val expired = prepared(context, createdAt = System.currentTimeMillis() - ThreeDays - 1)
            val unconfirmed = prepared(context, phase = "Ready")
            val future = prepared(context, createdAt = System.currentTimeMillis() + 60_000)
            val csv =
                File(context.cacheDir, "detection-exports/test.csv").apply {
                    parentFile!!.mkdirs()
                    writeText("column\nvalue")
                }
            withContext(Dispatchers.IO) {
                listOf(expired, unconfirmed, future).forEach { file ->
                    rejected { provider.openFile(FileProvider.getUriForFile(context, info.authority, file), "r") }
                }
                val csvUri = FileProvider.getUriForFile(context, info.authority, csv)
                provider.openFile(csvUri, "r").use { descriptor ->
                    assertArrayEquals(
                        csv.readBytes(),
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes(),
                    )
                }
            }
        }

    private fun prepared(
        context: Context,
        createdAt: Long = System.currentTimeMillis(),
        phase: String = "HandedOff",
    ): File {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
        val elapsed = SystemClock.elapsedRealtime()
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "diagnostics-prepared/$id").apply { mkdirs() }
        val file = File(directory, "ripdpi-diagnostics.zip")
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("summary.txt"))
            it.write("Final redacted summary".toByteArray())
            it.closeEntry()
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        File(directory, "lease.json").writeText(
            """
            {"leaseId":"$id","purpose":"ShareArchive","phase":"$phase","fileName":"${file.name}",
             "createdAt":$createdAt,"expiresAt":${createdAt + ThreeDays},"lastObservedAt":$createdAt,
             "createdElapsedMs":$elapsed,"lastObservedElapsedMs":$elapsed,"bootCount":1,
             "byteCount":${file.length()},"sha256":"$sha"}
            """.trimIndent(),
        )
        return file
    }

    private fun rejected(read: () -> Unit) {
        var rejected = false
        try {
            read()
        } catch (_: FileNotFoundException) {
            rejected = true
        }
        assertTrue("Invalid prepared URI was accepted", rejected)
    }

    private companion object {
        const val ThreeDays = 3L * 24 * 60 * 60 * 1000
    }
}
