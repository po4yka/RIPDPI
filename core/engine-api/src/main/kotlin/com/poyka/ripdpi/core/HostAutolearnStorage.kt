package com.poyka.ripdpi.core

import android.content.Context
import java.io.File
import java.io.IOException

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

fun clearHostAutolearnStore(context: Context): Boolean =
    fileOperationSucceeded { resolveHostAutolearnStoreFile(context).deleteIfPresent() }

/**
 * Records a user erase of the learned hosts. A running or starting engine keeps them in memory
 * and writes them back on persist and at shutdown, so the erase stays pending until
 * [applyPendingHostAutolearnErase] deletes the store before the next engine loads it.
 */
fun markHostAutolearnErasePending(context: Context): Boolean =
    fileOperationSucceeded { markHostAutolearnErasePending(resolveHostAutolearnStoreFile(context)) }

/**
 * Applies a pending erase. Call it only before a native runtime that loads the store starts, when
 * no engine holds the hosts in memory. Returns false, and keeps the erase pending, when the store
 * could not be deleted.
 *
 * Residual window: the caller (ProxyRuntimeSupervisor) knows only that its own previous native run
 * has returned. There is no process-wide "native proxy alive" signal, so if an older session's
 * native run outlived its stop timeout and flushes after a new session applied the erase, the old
 * hosts come back. A stop that times out already leaves that session in cleanup-pending state.
 */
fun applyPendingHostAutolearnErase(context: Context): Boolean =
    fileOperationSucceeded { applyPendingHostAutolearnErase(resolveHostAutolearnStoreFile(context)) }

internal fun markHostAutolearnErasePending(store: File): Boolean =
    fileOperationSucceeded {
        val marker = store.resolveSibling(HostAutolearnErasePendingFileName)
        marker.exists() || marker.createNewFile()
    }

internal fun applyPendingHostAutolearnErase(store: File): Boolean =
    fileOperationSucceeded {
        val marker = store.resolveSibling(HostAutolearnErasePendingFileName)
        !marker.exists() || (store.deleteIfPresent() && marker.deleteIfPresent())
    }

// Both helpers report failure as false so callers show "not cleared" instead of crashing.
private inline fun fileOperationSucceeded(operation: () -> Boolean): Boolean =
    try {
        operation()
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun File.deleteIfPresent(): Boolean = !exists() || delete()
