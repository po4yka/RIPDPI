package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogPathValidator
import com.poyka.ripdpi.proto.AppSettings
import java.io.File

internal fun AppSettings.effectiveDiagnosticTlsKeylogPath(appFilesDir: File): String? {
    if (!detectionCheckDebugModeEnabled || detectionCheckPrivacyModeEnabled) {
        return null
    }
    val configuredPath = detectionDiagnosticTlsKeylogPath.takeUnless { path -> path.isBlank() } ?: return null
    return TlsKeylogPathValidator(appFilesDir).validate(configuredPath)
}
