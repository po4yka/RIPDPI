package com.poyka.ripdpi.data

private const val UuidHex = "[0-9a-fA-F]"
private const val HyphenatedUuid = "$UuidHex{8}-$UuidHex{4}-$UuidHex{4}-$UuidHex{4}-$UuidHex{12}"
private val TuicUuidPattern =
    Regex("(?:$UuidHex{32}|$HyphenatedUuid|\\{$HyphenatedUuid}|(?i:urn:uuid:)$HyphenatedUuid)")

/** Accepts the textual UUID forms the native `uuid` crate parses, ignoring surrounding whitespace. */
fun isValidTuicUuid(value: String?): Boolean = value != null && TuicUuidPattern.matches(value.trim())
