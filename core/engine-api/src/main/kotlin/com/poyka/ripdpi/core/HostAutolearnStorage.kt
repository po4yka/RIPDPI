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

fun markHostAutolearnErasePending(context: Context): Boolean =
    fileOperationSucceeded { markHostAutolearnErasePending(resolveHostAutolearnStoreFile(context)) }

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

private inline fun fileOperationSucceeded(operation: () -> Boolean): Boolean =
    try {
        operation()
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun File.deleteIfPresent(): Boolean = !exists() || delete()
