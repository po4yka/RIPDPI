package com.poyka.ripdpi.proxyimport

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ImportFileDescriptorInstrumentedTest {
    @Test
    fun silentPipeReachesDeadlineAndReleasesDescriptor() {
        val (reader, writer) = ParcelFileDescriptor.createPipe()
        writer.use {
            val descriptor = reader.fileDescriptor
            val started = System.nanoTime()
            val result =
                AssetFileDescriptor(reader, 0, AssetFileDescriptor.UNKNOWN_LENGTH).use { asset ->
                    readImportDescriptor(asset, deadline(200)) { false }
                }
            assertNull(result)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            assertFalse(descriptor.valid())
        }
    }

    @Test
    fun bufferedPipeBytesAreReadBeforeHangup() {
        val (reader, writer) = ParcelFileDescriptor.createPipe()
        val text = "[Interface]\nPrivateKey = import-fixture\n"
        ParcelFileDescriptor.AutoCloseOutputStream(writer).use { it.write(text.toByteArray()) }
        AssetFileDescriptor(reader, 0, AssetFileDescriptor.UNKNOWN_LENGTH).use { asset ->
            assertEquals(text, readImportDescriptor(asset, deadline(1_000)) { false })
        }
    }

    @Test
    fun cancellationReleasesSilentPipeBeforeItsDeadline() {
        val (reader, writer) = ParcelFileDescriptor.createPipe()
        val cancelled = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        writer.use {
            val result =
                CompletableFuture.supplyAsync {
                    AssetFileDescriptor(reader, 0, AssetFileDescriptor.UNKNOWN_LENGTH).use { asset ->
                        readImportDescriptor(asset, deadline(3_000)) {
                            entered.countDown()
                            cancelled.get()
                        }
                    }
                }
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                cancelled.set(true)
                assertNull(result.get(1, TimeUnit.SECONDS))
            } finally {
                cancelled.set(true)
                result.get(4, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun regularFileReadsOnlyTheDeclaredAssetSlice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("import-slice", ".txt", context.cacheDir)
        try {
            file.writeText("prefix[Interface]suffix")
            val reader = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            Os.lseek(reader.fileDescriptor, 2, OsConstants.SEEK_SET)
            AssetFileDescriptor(reader, 6, 11).use { asset ->
                assertEquals("[Interface]", readImportDescriptor(asset, deadline(1_000)) { false })
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun unknownLengthFileRetainsTheImportSizeLimit() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("import-limit", ".txt", context.cacheDir)
        try {
            file.writeBytes(ByteArray(MaxImportFileBytes) { 'a'.code.toByte() })
            readFile(file).let { assertEquals(MaxImportFileBytes, it?.length) }
            file.appendText("a")
            assertNull(readFile(file))
        } finally {
            file.delete()
        }
    }

    private fun readFile(file: File): String? {
        val reader = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(reader, 0, AssetFileDescriptor.UNKNOWN_LENGTH).use { asset ->
            readImportDescriptor(asset, deadline(1_000)) { false }
        }
    }

    private fun deadline(millis: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
}
