package com.poyka.ripdpi.core

import android.content.Context
import java.io.File

private const val HostAutolearnDirectoryName = "ripdpi"
private const val HostAutolearnFileName = "host-autolearn-v2.json"
private const val HostAutolearnErasePendingFileName = "host-autolearn-v2.erase-pending"

fun resolveHostAutolearnStoreFile(context: Context): File {
    val dir = File(context.noBackupFilesDir, HostAutolearnDirectoryName)
    dir.mkdirs()
    return dir.resolve(HostAutolearnFileName)
}

fun resolveHostAutolearnStorePath(context: Context): String = resolveHostAutolearnStoreFile(context).absolutePath

fun hasHostAutolearnStore(context: Context): Boolean = resolveHostAutolearnStoreFile(context).exists()

fun clearHostAutolearnStore(context: Context): Boolean = resolveHostAutolearnStoreFile(context).deleteIfPresent()

/**
 * Records a user erase of the learned hosts. A running or starting engine keeps them in memory
 * and writes them back on persist and at shutdown, so the erase stays pending until
 * [applyPendingHostAutolearnErase] deletes the store before the next engine loads it.
 */
fun markHostAutolearnErasePending(context: Context): Boolean =
    markHostAutolearnErasePending(resolveHostAutolearnStoreFile(context))

/**
 * Applies a pending erase. Call it only before a native runtime that loads the store starts, when
 * no engine holds the hosts in memory. Returns false, and keeps the erase pending, when the store
 * could not be deleted.
 */
fun applyPendingHostAutolearnErase(context: Context): Boolean =
    applyPendingHostAutolearnErase(resolveHostAutolearnStoreFile(context))

internal fun markHostAutolearnErasePending(store: File): Boolean {
    val marker = store.resolveSibling(HostAutolearnErasePendingFileName)
    return marker.exists() || marker.createNewFile()
}

internal fun applyPendingHostAutolearnErase(store: File): Boolean {
    val marker = store.resolveSibling(HostAutolearnErasePendingFileName)
    if (!marker.exists()) return true
    return store.deleteIfPresent() && marker.deleteIfPresent()
}

private fun File.deleteIfPresent(): Boolean = !exists() || delete()
