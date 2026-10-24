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

    @Test
    fun `v2 AWG wire fields reject legacy integers unsupported profiles and overlapping ranges`() {
        val noFingerprint = valid - "cohort_fingerprint"
        val invalid =
            listOf(
                JsonObject(noFingerprint - "wire_profile"),
                JsonObject(noFingerprint + ("wire_profile" to JsonPrimitive("future"))),
                JsonObject(noFingerprint + ("h1" to JsonPrimitive(1000))),
                JsonObject(noFingerprint + ("h1" to JsonPrimitive("1000-2000"))),
                JsonObject(noFingerprint + ("i1" to JsonPrimitive("deadbeef"))),
                JsonObject(noFingerprint + ("disable_cookies" to JsonPrimitive(true))),
                JsonObject(noFingerprint - "jc"),
                JsonObject(noFingerprint + ("jc" to JsonPrimitive(Int.MAX_VALUE))),
                JsonObject(noFingerprint + ("jc" to JsonPrimitive("4"))),
                JsonObject(noFingerprint + ("jmin" to JsonPrimitive(-1))),
                JsonObject(noFingerprint + ("jmax" to JsonPrimitive(1281))),
                JsonObject(noFingerprint + ("i1" to JsonPrimitive(""))),
                JsonObject(noFingerprint + ("s1" to JsonPrimitive(0)) + ("s2" to JsonPrimitive(56))),
            )
        invalid.forEach { entry ->
            val parsed = parseEntries(entry, valid)
            assertEquals(1, parsed.amneziaWgProfiles.size)
            assertEquals(SingBoxSkipReason.UNSUPPORTED_OBFUSCATION, parsed.skipped.single().reason)
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
