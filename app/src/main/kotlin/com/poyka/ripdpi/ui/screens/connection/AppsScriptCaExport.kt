package com.poyka.ripdpi.ui.screens.connection

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.services.AppsScriptRelayCertificateStore
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppsScriptCaExport() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var disclosure by remember { mutableStateOf(false) }
    val title = stringResource(R.string.config_relay_apps_script_export_ca)
    val failure = stringResource(R.string.config_relay_apps_script_ca_unavailable)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")) { uri ->
            if (uri != null) {
                scope.launch {
                    val saved =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val certificate = AppsScriptRelayCertificateStore(context.filesDir).publicCertificate()
                                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                                    it.write(certificate)
                                }
                            }.isSuccess
                        }
                    if (!saved) Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                }
            }
        }
    RipDpiButton(text = title, onClick = { disclosure = true }, variant = RipDpiButtonVariant.Outline)
    if (disclosure) {
        RipDpiDialog(
            onDismissRequest = { disclosure = false },
            title = title,
            dismissAction =
                RipDpiDialogAction(
                    stringResource(R.string.action_dismiss),
                    onClick = { disclosure = false },
                ),
            confirmAction =
                RipDpiDialogAction(title, onClick = {
                    disclosure = false
                    launcher.launch("ripdpi-apps-script-ca.crt")
                }),
            visuals = RipDpiDialogVisuals(message = stringResource(R.string.config_relay_apps_script_ca_trust)),
        )
    }
}
