package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgCohortFingerprintImportTest {
    private val bundle = resource("/contract/ripdpi-bundle.golden-full.json")
    private val ripdpi = bundle.getValue("ripdpi").jsonObject
    private val valid =
        ripdpi
            .getValue("amneziawg")
            .jsonArray
            .single()
            .jsonObject

    @Test
    fun `valid fingerprint and absent legacy metadata remain importable`() {
        val parsed = parseEntries(valid, JsonObject(valid - "cohort_fingerprint"))

        assertEquals(2, parsed.amneziaWgProfiles.size)
        assertTrue(parsed.skipped.isEmpty())
        assertEquals(null, parsed.amneziaWgProfiles.last().cohortFingerprint)
    }

    @Test
    fun `malformed and mismatched fingerprints are rejected without losing siblings`() {
        val invalidValues =
            listOf(
                JsonPrimitive("sha256:" + "0".repeat(64)),
                JsonPrimitive("secret-fingerprint-value"),
                JsonPrimitive(""),
                JsonPrimitive(" ${valid.getValue("cohort_fingerprint").toString().trim('"')} "),
                JsonNull,
                JsonPrimitive(123),
                JsonObject(emptyMap()),
                JsonArray(emptyList()),
            )
        invalidValues.forEach { fingerprint ->
            val parsed = parseEntries(withFingerprint(fingerprint), valid)

            assertEquals("invalid value must not produce an AWG profile", 1, parsed.amneziaWgProfiles.size)
            assertEquals(1, parsed.skipped.size)
            assertEquals(SingBoxSkipReason.UNSUPPORTED_FINGERPRINT, parsed.skipped.single().reason)
            assertEquals("cohort_fingerprint", parsed.skipped.single().detail)
            assertTrue(parsed.profiles.isNotEmpty())
        }
    }

    @Test
    fun `parameter drift invalidates the original fingerprint`() {
        val changed = JsonObject(valid + ("jc" to JsonPrimitive(19)))
        val parsed = parseEntries(changed)

        assertTrue(parsed.amneziaWgProfiles.isEmpty())
        assertEquals("cohort_fingerprint", parsed.skipped.single().detail)
    }

    @Test
    fun `server negative fingerprint fixtures are rejected`() {
        listOf("neg-bad-cohort-fingerprint.json", "neg-fingerprint-mismatch.json").forEach { name ->
            val parsed = parse(resource("/contract/negative/$name"))

            assertTrue(parsed.amneziaWgProfiles.isEmpty())
            assertEquals("cohort_fingerprint", parsed.skipped.single().detail)
        }
    }

    private fun withFingerprint(value: JsonElement): JsonObject = JsonObject(valid + ("cohort_fingerprint" to value))

    private fun parseEntries(vararg entries: JsonObject): SingBoxParseResult.Success =
        parse(JsonObject(bundle + ("ripdpi" to JsonObject(ripdpi + ("amneziawg" to JsonArray(entries.toList()))))))

    private fun parse(document: JsonObject): SingBoxParseResult.Success =
        SingBoxSubscriptionParser.parse(document.toString(), "cohort-test") as SingBoxParseResult.Success

    private fun resource(path: String): JsonObject {
        val text = requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }
}
