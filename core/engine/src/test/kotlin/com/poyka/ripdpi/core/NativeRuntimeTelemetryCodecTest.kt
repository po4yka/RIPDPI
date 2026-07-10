package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeTelemetrySchemaVersion
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeRuntimeTelemetryCodecTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `current schema accepts unknown additive fields`() {
        val snapshot =
            json.decodeNativeRuntimeSnapshot(
                """{"source":"proxy","schemaVersion":2,"futureField":true}""",
            )

        assertEquals(NativeRuntimeTelemetrySchemaVersion, snapshot.schemaVersion)
        assertEquals("proxy", snapshot.source)
    }

    @Test
    fun `missing schema version is rejected`() {
        assertThrows(SerializationException::class.java) {
            json.decodeNativeRuntimeSnapshot("""{"source":"proxy"}""")
        }
    }

    @Test
    fun `non-current schema versions are rejected`() {
        listOf(1, 3).forEach { version ->
            assertThrows(IllegalArgumentException::class.java) {
                json.decodeNativeRuntimeSnapshot(
                    """{"source":"proxy","schemaVersion":$version}""",
                )
            }
        }
    }
}
