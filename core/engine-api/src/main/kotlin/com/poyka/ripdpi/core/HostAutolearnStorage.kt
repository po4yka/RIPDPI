package com.poyka.ripdpi.core

import android.content.Context
import java.io.File

private const val HostAutolearnDirectoryName = "ripdpi"
private const val HostAutolearnFileName = "host-autolearn-v2.json"

fun resolveHostAutolearnStoreFile(context: Context): File {
    val dir = File(context.noBackupFilesDir, HostAutolearnDirectoryName)
    dir.mkdirs()
    return dir.resolve(HostAutolearnFileName)
}

fun resolveHostAutolearnStorePath(context: Context): String = resolveHostAutolearnStoreFile(context).absolutePath

fun hasHostAutolearnStore(context: Context): Boolean = resolveHostAutolearnStoreFile(context).exists()

fun clearHostAutolearnStore(context: Context): Boolean {
    val current = resolveHostAutolearnStoreFile(context)
    return !current.exists() || current.delete()
}
