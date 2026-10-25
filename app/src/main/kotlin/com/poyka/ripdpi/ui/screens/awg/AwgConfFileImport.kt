package com.poyka.ripdpi.ui.screens.awg

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns an action that opens the system file picker for an AmneziaWG `.conf`, stages the
 * text outside saved state (it carries the private key), and calls [onOpenEditor] with the
 * editor route that claims it. An unreadable or oversized file is staged as empty text, so
 * the editor reports the failure instead of opening silently blank.
 */
@Composable
internal fun rememberAwgConfFileImport(onOpenEditor: (Route) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val conf = readConfText(context, uri).orEmpty()
                    val token = PendingProxyImportStore.process.stageAmneziaWgConf(conf) ?: return@launch
                    onOpenEditor(Route.AmneziaWgProfile(importToken = token))
                }
            }
        }
    // `.conf` has no registered MIME type; providers report it as text/plain or octet-stream.
    return { launcher.launch(arrayOf("*/*")) }
}

private suspend fun readConfText(
    context: Context,
    uri: Uri,
): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(PendingProxyImportStore.MaxAmneziaWgConfBytes + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                decodeConfText(buffer.copyOf(total))
            }
        }.getOrNull()
    }

/**
 * Decodes picked file bytes as `.conf` text. Returns `null` for an oversized or non-UTF-8
 * (binary) file; strips a UTF-8 byte-order mark, which the conf parser does not trim.
 */
internal fun decodeConfText(bytes: ByteArray): String? {
    if (bytes.size > PendingProxyImportStore.MaxAmneziaWgConfBytes) return null
    val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    return text.takeUnless { '\uFFFD' in it }
}
