package com.poyka.ripdpi.proxyimport

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import java.io.FileNotFoundException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ImportTextReaderTest {
    @Test
    fun `shared file read times out even when provider open ignores cancellation`() {
        val provider = StalledImportProvider(blockCancellation = true)
        val uri = provider.register("stalled-import")
        val result = readAsync(uri)
        try {
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS))
            assertNull(result.get(4, TimeUnit.SECONDS))
            assertTrue(provider.cancellationEntered.await(1, TimeUnit.SECONDS))
        } finally {
            provider.release()
            assertTrue(provider.returned.await(2, TimeUnit.SECONDS))
            result.get(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `stalled opens cannot admit or queue further imports`() {
        val provider = StalledImportProvider(expectedOpens = 2)
        val uri = provider.register("saturated-import")
        val pending = List(2) { readAsync(uri) }
        try {
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS))
            val rejected =
                CompletableFuture.supplyAsync {
                    runBlocking {
                        List(
                            20,
                        ) { readImportText(resolver(), uri) }
                    }
                }
            assertEquals(List<String?>(20) { null }, rejected.get(1, TimeUnit.SECONDS))
            pending.forEach { assertNull(it.get(4, TimeUnit.SECONDS)) }
        } finally {
            provider.release()
            assertTrue(provider.returned.await(2, TimeUnit.SECONDS))
            pending.forEach { it.get(2, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `utf-8 byte-order mark is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[Interface]".toByteArray()
        assertEquals("[Interface]", decodeImportText(bytes))
    }

    @Test
    fun `binary file is rejected`() {
        assertNull(decodeImportText(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0xFF.toByte())))
    }

    @Test
    fun `oversized file is rejected`() {
        assertNull(decodeImportText(ByteArray(MaxImportFileBytes + 1) { 'a'.code.toByte() }))
    }

    private fun resolver() = ApplicationProvider.getApplicationContext<Context>().contentResolver

    private fun readAsync(uri: Uri): CompletableFuture<String?> =
        CompletableFuture.supplyAsync { runBlocking { readImportText(resolver(), uri) } }
}

internal class StalledImportProvider(
    expectedOpens: Int = 1,
    private val blockCancellation: Boolean = false,
) : ContentProvider() {
    val entered = CountDownLatch(expectedOpens)
    val returned = CountDownLatch(expectedOpens)
    val cancellationEntered = CountDownLatch(1)
    private val releaseOpen = CountDownLatch(1)
    private val releaseCancellation = CountDownLatch(1)

    fun register(authority: String): Uri {
        attachInfo(ApplicationProvider.getApplicationContext(), ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, this)
        return Uri.parse("content://$authority/config")
    }

    fun release() {
        releaseOpen.countDown()
        releaseCancellation.countDown()
    }

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? {
        signal?.setOnCancelListener {
            cancellationEntered.countDown()
            if (blockCancellation) check(releaseCancellation.await(10, TimeUnit.SECONDS))
        }
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts, signal)
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        entered.countDown()
        try {
            check(releaseOpen.await(10, TimeUnit.SECONDS))
            throw FileNotFoundException("Provider stopped")
        } finally {
            returned.countDown()
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/plain"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
