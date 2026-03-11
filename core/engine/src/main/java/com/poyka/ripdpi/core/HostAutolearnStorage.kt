package com.poyka.ripdpi.core

import android.content.Context
import java.io.File

private const val HostAutolearnDirectoryName = "ripdpi"
private const val HostAutolearnFileName = "host-autolearn-v1.json"

fun resolveHostAutolearnStoreFile(context: Context): File =
    File(context.noBackupFilesDir, HostAutolearnDirectoryName).resolve(HostAutolearnFileName)

fun resolveHostAutolearnStorePath(context: Context): String = resolveHostAutolearnStoreFile(context).absolutePath

fun clearHostAutolearnStore(context: Context): Boolean {
    val file = resolveHostAutolearnStoreFile(context)
    return !file.exists() || file.delete()
}
