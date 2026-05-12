package com.poyka.ripdpi.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DpiSuiteSettingsTest {
    @Test
    fun `dpi suite concurrency default is persisted seed value`() {
        assertEquals(100, AppSettingsSerializer.defaultValue.dpiSuiteConcurrency)
    }

    @Test
    fun `random diagnostic hostname mode defaults off`() {
        assertEquals(false, AppSettingsSerializer.defaultValue.detectionDiagnosticRandomHostnamesEnabled)
    }

    @Test
    fun `tls keylog path defaults empty`() {
        assertEquals("", AppSettingsSerializer.defaultValue.detectionDiagnosticTlsKeylogPath)
    }

    @Test
    fun `dpi suite concurrency round trips through app settings serializer`() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDpiSuiteConcurrency(42)
                    .build()
            val output = ByteArrayOutputStream()

            AppSettingsSerializer.writeTo(settings, output)
            val decoded = AppSettingsSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))

            assertEquals(42, decoded.dpiSuiteConcurrency)
        }

    @Test
    fun `random diagnostic hostname mode round trips through app settings serializer`() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDetectionDiagnosticRandomHostnamesEnabled(true)
                    .build()
            val output = ByteArrayOutputStream()

            AppSettingsSerializer.writeTo(settings, output)
            val decoded = AppSettingsSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))

            assertEquals(true, decoded.detectionDiagnosticRandomHostnamesEnabled)
        }

    @Test
    fun `tls keylog path round trips through app settings serializer`() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDetectionDiagnosticTlsKeylogPath("/app/files/diagnostics/tls.keys")
                    .build()
            val output = ByteArrayOutputStream()

            AppSettingsSerializer.writeTo(settings, output)
            val decoded = AppSettingsSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))

            assertEquals("/app/files/diagnostics/tls.keys", decoded.detectionDiagnosticTlsKeylogPath)
        }
}
