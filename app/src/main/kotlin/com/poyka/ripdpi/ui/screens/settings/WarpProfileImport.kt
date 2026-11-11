package com.poyka.ripdpi.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.poyka.ripdpi.proxyimport.readImportText

@Composable
internal fun rememberWarpProfileImport(viewModel: WarpEnrollmentViewModel): () -> Unit {
    val context = LocalContext.current
    val contentResolver = remember(context) { context.applicationContext.contentResolver }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.importProfile { readImportText(contentResolver, uri) }
            }
        }
    return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
}
