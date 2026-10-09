package com.poyka.ripdpi.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RipDpiEvidenceJsonContractTest {
    private val payload = EvidenceJsonPayload(requiredNullable = null)
    private val inbound = """{"requiredNullable":null,"futureField":true}"""

    @Test
    fun `tolerant inbound JSON keeps required nulls and omits default values`() {
        assertEquals(payload, RipDpiJson.decodeFromString(EvidenceJsonPayload.serializer(), inbound))
        assertEquals(
            """{"requiredNullable":null}""",
            RipDpiJson.encodeToString(EvidenceJsonPayload.serializer(), payload),
        )
    }

    @Test
    fun `tolerant evidence JSON writes defaults and explicit nulls`() {
        assertEquals(
            payload,
            RipDpiTolerantEncodeDefaultsJson.decodeFromString(EvidenceJsonPayload.serializer(), inbound),
        )
        assertEquals(
            """{"requiredNullable":null,"optionalNullable":null,"count":7}""",
            RipDpiTolerantEncodeDefaultsJson.encodeToString(EvidenceJsonPayload.serializer(), payload),
        )
    }

    @Test
    fun `strict evidence JSON writes defaults and explicit nulls but rejects unknown keys`() {
        assertEquals(
            """{"requiredNullable":null,"optionalNullable":null,"count":7}""",
            RipDpiEncodeDefaultsJson.encodeToString(EvidenceJsonPayload.serializer(), payload),
        )
        assertThrows(SerializationException::class.java) {
            RipDpiEncodeDefaultsJson.decodeFromString(EvidenceJsonPayload.serializer(), inbound)
        }
    }
}

@Serializable
private data class EvidenceJsonPayload(
    val requiredNullable: String?,
    val optionalNullable: String? = null,
    val count: Int = 7,
)
