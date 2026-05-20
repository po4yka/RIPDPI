package com.poyka.ripdpi.proxyimport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow seam over the system [ClipboardManager], introduced so the clipboard-import
 * ViewModel can be unit-tested without a real `ClipboardManager` and so the privacy
 * contract is enforceable: the only operations exposed are a single explicit read and an
 * explicit clear. There is deliberately no listener / observer hook — RIPDPI never
 * watches the clipboard in the background.
 */
interface ClipboardReader {
    /**
     * Performs a single read of the primary clip's text. Returns `null` when the
     * clipboard is empty or holds no text item. On Android 12+ this triggers the system's
     * clipboard-access toast — that is expected and intentionally not suppressed.
     */
    fun readPrimaryClipText(): String?

    /** Replaces the primary clip with an empty clip, dropping any persisted credential. */
    fun clearPrimaryClip()
}

/**
 * [ClipboardReader] backed by the real system [ClipboardManager]. Every method is a
 * one-shot operation invoked only from an explicit user action; nothing here registers a
 * listener.
 */
@Singleton
class SystemClipboardReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ClipboardReader {
        private val clipboardManager: ClipboardManager?
            get() = context.getSystemService()

        @Suppress("ReturnCount")
        override fun readPrimaryClipText(): String? {
            val manager = clipboardManager ?: return null
            val clip = manager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            return clip.getItemAt(0)?.text?.toString()
        }

        override fun clearPrimaryClip() {
            val manager = clipboardManager ?: return
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ClipboardReaderModule {
    @Binds
    @Singleton
    abstract fun bindClipboardReader(reader: SystemClipboardReader): ClipboardReader
}
