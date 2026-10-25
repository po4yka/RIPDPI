package com.poyka.ripdpi.ui.screens.awg

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.poyka.ripdpi.proxyimport.ImportPayload
import com.poyka.ripdpi.proxyimport.ImportPayloadClassifier
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.proxyimport.readImportText
import com.poyka.ripdpi.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns an action that opens the system file picker for a WireGuard / AmneziaWG `.conf`
 * (or a file that holds an AmneziaVPN `vpn://` key), stages the text outside saved state
 * (it carries the private key), and calls [onOpenEditor] with the editor route that claims
 * it. An unreadable, oversized, or binary file is staged as empty text, so the editor reports
 * "could not read the file" instead of opening silently blank.
 */
@Composable
internal fun rememberAwgConfFileImport(onOpenEditor: (Route) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val text = withContext(Dispatchers.IO) { readImportText(context.contentResolver, uri) }.orEmpty()
                    val conf = (ImportPayloadClassifier.classify(text) as? ImportPayload.WireGuardConf)?.conf ?: text
                    val token = PendingProxyImportStore.process.stageAmneziaWgConf(conf) ?: return@launch
                    onOpenEditor(Route.AmneziaWgProfile(importToken = token))
                }
            }
        }
    // `.conf` has no registered MIME type; providers report it as text/plain or octet-stream.
    return { launcher.launch(arrayOf("*/*")) }
}
