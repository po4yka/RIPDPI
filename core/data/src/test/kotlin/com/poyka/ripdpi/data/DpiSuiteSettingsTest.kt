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
}
