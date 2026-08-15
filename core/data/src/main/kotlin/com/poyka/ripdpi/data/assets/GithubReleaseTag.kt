package com.poyka.ripdpi.data.assets

import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts the `tag_name` field from a GitHub `/releases/latest` JSON response. Returns null when
 * the payload is not valid JSON, lacks `tag_name`, or carries a blank tag. Pure and JVM-testable;
 * no network access happens here (the caller in `core:service` fetches the bytes).
 */
fun parseGithubLatestReleaseTag(json: String): String? =
    runCatching {
        val root = githubReleaseJson.parseToJsonElement(json) as? JsonObject ?: return null
        root["tag_name"]
            ?.jsonPrimitive
            ?.contentOrNullSafe()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

private val githubReleaseJson =
    RipDpiLenientJson
