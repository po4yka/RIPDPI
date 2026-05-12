package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DiagnosticsTlsKeylogPathTest {
    @Test
    fun effectiveDiagnosticTlsKeylogPathReturnsValidatedPathWhenDebugIsEnabled() {
        val filesDir = createTempDirectory().toFile()
        val keylogPath = filesDir.resolve("diagnostics/tls.keys")
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDetectionCheckDebugModeEnabled(true)
                .setDetectionDiagnosticTlsKeylogPath(keylogPath.path)
                .build()

        val result = settings.effectiveDiagnosticTlsKeylogPath(filesDir)

        assertEquals(keylogPath.canonicalPath, result)
    }

    @Test
    fun effectiveDiagnosticTlsKeylogPathIsDisabledByPrivacyMode() {
        val filesDir = createTempDirectory().toFile()
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDetectionCheckDebugModeEnabled(true)
                .setDetectionCheckPrivacyModeEnabled(true)
                .setDetectionDiagnosticTlsKeylogPath(filesDir.resolve("tls.keys").path)
                .build()

        assertNull(settings.effectiveDiagnosticTlsKeylogPath(filesDir))
    }

    @Test
    fun effectiveDiagnosticTlsKeylogPathRejectsOutsideAppStorage() {
        val filesDir = createTempDirectory().toFile()
        val outside = createTempDirectory().toFile().resolve("tls.keys")
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDetectionCheckDebugModeEnabled(true)
                .setDetectionDiagnosticTlsKeylogPath(outside.path)
                .build()

        val error =
            runCatching {
                settings.effectiveDiagnosticTlsKeylogPath(filesDir)
            }.exceptionOrNull()

        assertEquals("TLS keylog path must be inside app storage", error?.message)
    }

    private val File.canonicalPath: String get() = canonicalFile.absolutePath
}
