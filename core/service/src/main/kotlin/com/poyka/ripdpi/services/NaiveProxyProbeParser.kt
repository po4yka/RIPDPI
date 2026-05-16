package com.poyka.ripdpi.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parsed `RIPDPI-PROBE` line emitted by the native NaiveProxy helper
 * on `--probe` exit. The Kotlin service-side manager invokes the
 * helper with `--probe` before any real start, parses the single
 * `RIPDPI-PROBE { ... }` line, and refuses launch if the schema
 * version is outside the supported range.
 *
 * Pairs with the Rust side in
 * `native/rust/crates/ripdpi-naiveproxy/src/main.rs::render_probe_line`.
 */
@Serializable
data class NaiveProxyProbe(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("helper_version") val helperVersion: String,
    @SerialName("features") val features: List<String> = emptyList(),
) {
    /**
     * Whether the helper's schema version falls within the
     * manager-supported range. Used by `NaiveProxyManager` to refuse
     * launch on mismatch.
     */
    fun isSchemaSupported(supportedRange: IntRange): Boolean = schemaVersion in supportedRange
}

object NaiveProxyProbeParser {
    private const val MARKER = "RIPDPI-PROBE "

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    /**
     * Parse a single line from the NaiveProxy helper's `--probe`
     * output. Returns `null` if the line is missing the marker, the
     * JSON is malformed, or required fields are absent.
     */
    fun parse(line: String): NaiveProxyProbe? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(MARKER)) {
            return null
        }
        val payload = trimmed.substring(MARKER.length)
        return runCatching { json.decodeFromString<NaiveProxyProbe>(payload) }.getOrNull()
    }
}
