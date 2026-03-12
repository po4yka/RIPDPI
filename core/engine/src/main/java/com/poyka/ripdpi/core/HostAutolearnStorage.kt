package com.poyka.ripdpi.core

import android.content.Context
import java.io.File

private const val HostAutolearnDirectoryName = "ripdpi"
private const val HostAutolearnFileName = "host-autolearn-v2.json"
private const val LegacyHostAutolearnFileName = "host-autolearn-v1.json"

fun resolveHostAutolearnStoreFile(context: Context): File =
    File(context.noBackupFilesDir, HostAutolearnDirectoryName).resolve(HostAutolearnFileName)

fun resolveLegacyHostAutolearnStoreFile(context: Context): File =
    File(context.noBackupFilesDir, HostAutolearnDirectoryName).resolve(LegacyHostAutolearnFileName)

fun resolveHostAutolearnStorePath(context: Context): String = resolveHostAutolearnStoreFile(context).absolutePath

fun hasHostAutolearnStore(context: Context): Boolean =
    resolveHostAutolearnStoreFile(context).exists() || resolveLegacyHostAutolearnStoreFile(context).exists()

fun clearHostAutolearnStore(context: Context): Boolean {
    val current = resolveHostAutolearnStoreFile(context)
    val legacy = resolveLegacyHostAutolearnStoreFile(context)
    val currentCleared = !current.exists() || current.delete()
    val legacyCleared = !legacy.exists() || legacy.delete()
    return currentCleared && legacyCleared
}
