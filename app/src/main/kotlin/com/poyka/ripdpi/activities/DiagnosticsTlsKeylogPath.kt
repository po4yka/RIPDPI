package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogPathValidator
import com.poyka.ripdpi.proto.AppSettings
import java.io.File

internal fun AppSettings.effectiveDiagnosticTlsKeylogPath(appFilesDir: File): String? {
    val configuredPath =
        detectionDiagnosticTlsKeylogPath
            .takeIf { detectionCheckDebugModeEnabled && !detectionCheckPrivacyModeEnabled }
            ?.takeUnless { path -> path.isBlank() }
    return configuredPath?.let { path -> TlsKeylogPathValidator(appFilesDir).validate(path) }
}
