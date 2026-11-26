package com.poyka.ripdpi.ui.screens.connection

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class AwgConfShare(
    val name: String,
    val conf: String,
) {
    override fun toString(): String = "AwgConfShare(<redacted>)"
}

private const val ShareDirectory = "profile-share"
private const val RetentionMs = 24 * 60 * 60 * 1000L
private const val MaxInterfaceNameLength = 15
private val InterfaceNameUnsafe = Regex("[^A-Za-z0-9_=+.-]")

internal fun awgConfFileName(serverName: String): String =
    serverName
        .replace(InterfaceNameUnsafe, "_")
        .take(MaxInterfaceNameLength)
        .trim('_')
        .ifEmpty { "amneziawg" } + ".conf"

internal fun pruneAwgConfShares(
    cacheDir: File,
    nowMs: Long = System.currentTimeMillis(),
) {
    File(cacheDir, ShareDirectory).listFiles()?.forEach { entry ->
        val ageMs = nowMs - entry.lastModified()
        if (ageMs >= RetentionMs || ageMs < 0L) entry.deleteRecursively()
    }
}

internal fun writeAwgConfShareFile(
    cacheDir: File,
    share: AwgConfShare,
): File {
    pruneAwgConfShares(cacheDir)
    val directory = File(File(cacheDir, ShareDirectory), UUID.randomUUID().toString()).apply { mkdirs() }
    return File(directory, awgConfFileName(share.name)).apply { writeText(share.conf) }
}

private fun openAwgConfShareSheet(
    context: Context,
    file: File,
): Boolean =
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.profile.fileprovider", file)
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, null))
    }.isSuccess

@Composable
internal fun AwgConfShareEffect(servers: ServerListViewModel) {
    val context = LocalContext.current
    LaunchedEffect(servers, context) {
        servers.awgShares.collect { share ->
            var file: File? = null
            var opened = false
            try {
                file = withContext(Dispatchers.IO) { writeAwgConfShareFile(context.cacheDir, share) }
                opened = openAwgConfShareSheet(context, file)
            } catch (_: IOException) {
                opened = false
            } finally {
                if (!opened) file?.parentFile?.deleteRecursively()
            }
            if (!opened) servers.shareFailed()
        }
    }
}
