package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Native xHTTP needs TLS and verifies the certificate without ECH, so a sing-box plain VLESS xHTTP node
 * that disables TLS or asks for insecure, ECH or custom certificates stays inert instead of losing that
 * option. A tls object without "enabled" counts as TLS, as in the committed RIPDPI bundles
 * (scripts/fixtures/embedded-relay-bundle/ci-fixture.json).
 */
internal fun JsonObject?.isNativeXhttpTls(): Boolean =
    this != null &&
        flag("enabled") != false &&
        flag("insecure") != true &&
        (this["ech"] as? JsonObject)?.flag("enabled") != true &&
        this["certificate"] == null &&
        this["certificate_path"] == null

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
