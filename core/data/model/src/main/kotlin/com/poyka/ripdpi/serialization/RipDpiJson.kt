package com.poyka.ripdpi.serialization

import kotlinx.serialization.json.Json

/** Primary JSON instance for tolerant inbound app/runtime payloads. */
val RipDpiJson: Json =
    Json {
        ignoreUnknownKeys = true
    }

/** Inbound parser for third-party payloads that may use relaxed JSON syntax. */
val RipDpiLenientJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/** Persistent/contract JSON that must keep default values and omit explicit nulls. */
val RipDpiContractJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

/** Contract encoder for native payloads whose schema requires default values but preserves default null handling. */
val RipDpiEncodeDefaultsJson: Json =
    Json {
        encodeDefaults = true
    }

/** Native/request payloads that intentionally omit explicit nulls without forcing default values. */
val RipDpiNoExplicitNullsJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/** Pretty JSON for user-facing exports that intentionally omit explicit nulls. */
val RipDpiPrettyJson: Json =
    Json {
        prettyPrint = true
        explicitNulls = false
    }

/** Pretty JSON for reports that previously only enabled indentation. */
val RipDpiPrettyDefaultsJson: Json =
    Json {
        prettyPrint = true
    }

/** Pretty tolerant JSON for catalog payloads that are both parsed and emitted. */
val RipDpiPrettyTolerantJson: Json =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

/** Pretty contract JSON for diagnostic/settings exports that need default values and forward-compatible parsing. */
val RipDpiPrettyContractJson: Json =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

/** Backup archives use a stable two-space pretty format; changing this changes exported backup bytes. */
val RipDpiBackupJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

/** Native proxy config uses a non-default sealed-class discriminator consumed by Rust. */
val RipDpiNativeProxyJson: Json =
    Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
