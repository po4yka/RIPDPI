package com.poyka.ripdpi.e2e

import androidx.test.platform.app.InstrumentationRegistry
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail

internal object GoldenContractSupport {
    fun assertJsonAsset(
        assetPath: String,
        actualJson: String,
        scrub: (String) -> String = { it },
    ) {
        val actual = canonicalJson(scrub(actualJson))
        val expected =
            InstrumentationRegistry.getInstrumentation().context.assets.open(assetPath).bufferedReader().use { reader ->
                canonicalJson(reader.readText())
            }
        if (expected != actual) {
            fail("Golden mismatch for $assetPath\n--- expected ---\n$expected\n--- actual ---\n$actual")
        }
    }

    fun scrubCommonTelemetryJson(
        payload: String,
        port: Int? = null,
    ): String {
        var text = payload.replace("\r\n", "\n")
        text = Regex("\"capturedAt\"\\s*:\\s*\\d+").replace(text, "\"capturedAt\":0")
        text = Regex("\"createdAt\"\\s*:\\s*\\d+").replace(text, "\"createdAt\":0")
        port?.let { resolvedPort ->
            text = text.replace("127.0.0.1:$resolvedPort", "127.0.0.1:<port>")
        }
        return text
    }

    private fun canonicalJson(value: String): String =
        when (val sorted = sortJson(parse(value))) {
            is JSONObject -> sorted.toString(2)
            is JSONArray -> sorted.toString(2)
            else -> error("Expected canonical JSON container")
        }

    private fun parse(value: String): Any {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> error("Expected JSON payload")
        }
    }

    private fun sortJson(value: Any): Any =
        when (value) {
            is JSONObject -> {
                val sorted = JSONObject()
                value.keys().asSequence().toList().sorted().forEach { key ->
                    sorted.put(key, sortJson(value.get(key)))
                }
                sorted
            }

            is JSONArray -> JSONArray().also { target ->
                for (index in 0 until value.length()) {
                    target.put(sortJson(value.get(index)))
                }
            }

            else -> value
        }
}
