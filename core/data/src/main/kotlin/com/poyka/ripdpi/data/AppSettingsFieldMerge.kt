package com.poyka.ripdpi.data

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.poyka.ripdpi.proto.AppSettings

/**
 * Three-way merge of top-level [AppSettings] fields: every field whose encoding differs between
 * [base] and [after] takes its [after] value (or is cleared), every other field keeps [current].
 *
 * An editor builds [after] from the [base] snapshot it read earlier. Writing [after] whole would
 * revert any setting another writer changed since then. Repeated and nested fields merge as one
 * unit, so a conflicting change to the same field resolves to [after].
 *
 * The `relay_*` fields describe one selected relay profile, so they also merge as one unit: when
 * the edit changed any of them, all of them come from [after]. Otherwise a concurrent selection
 * of another profile could keep its id with the edited profile's server or keys.
 */
internal fun mergeChangedSettingsFields(
    base: AppSettings,
    after: AppSettings,
    current: AppSettings,
): AppSettings {
    val baseFields = topLevelFields(base.toByteString())
    val afterFields = topLevelFields(after.toByteString())
    val currentFields = topLevelFields(current.toByteString())
    val relayEdited = RelaySettingsFieldNumbers.any { baseFields[it] != afterFields[it] }
    val merged =
        (baseFields.keys + afterFields.keys + currentFields.keys)
            .sorted()
            .mapNotNull { field ->
                val fromAfter =
                    baseFields[field] != afterFields[field] || (relayEdited && field in RelaySettingsFieldNumbers)
                if (fromAfter) afterFields[field] else currentFields[field]
            }
    return AppSettings.parseFrom(ByteString.copyFrom(merged))
}

/** Raw tag-and-value bytes of each top-level field, all occurrences concatenated in wire order. */
private fun topLevelFields(bytes: ByteString): Map<Int, ByteString> {
    val fields = LinkedHashMap<Int, ByteString>()
    val input = CodedInputStream.newInstance(bytes.toByteArray())
    while (true) {
        val start = input.totalBytesRead
        val tag = input.readTag()
        if (tag == 0) break
        input.skipField(tag)
        val field = tag ushr TagTypeBits
        val occurrence = bytes.substring(start, input.totalBytesRead)
        fields[field] = fields[field]?.concat(occurrence) ?: occurrence
    }
    return fields
}

private const val TagTypeBits = 3

/** Every `relay_*` field of [AppSettings]; `AppSettingsFieldMergeTest` keeps this list complete. */
internal val RelaySettingsFieldNumbers: Set<Int> =
    setOf(
        AppSettings.RELAY_ENABLED_FIELD_NUMBER,
        AppSettings.RELAY_OUTBOUND_BIND_IP_FIELD_NUMBER,
        AppSettings.RELAY_DNS_OVER_TUNNEL_ENABLED_FIELD_NUMBER,
        AppSettings.RELAY_KIND_FIELD_NUMBER,
        AppSettings.RELAY_PROFILE_ID_FIELD_NUMBER,
        AppSettings.RELAY_SERVER_FIELD_NUMBER,
        AppSettings.RELAY_SERVER_PORT_FIELD_NUMBER,
        AppSettings.RELAY_SERVER_NAME_FIELD_NUMBER,
        AppSettings.RELAY_REALITY_PUBLIC_KEY_FIELD_NUMBER,
        AppSettings.RELAY_REALITY_SHORT_ID_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_ENTRY_SERVER_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_ENTRY_PORT_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_ENTRY_SERVER_NAME_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_ENTRY_PUBLIC_KEY_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_ENTRY_SHORT_ID_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_EXIT_SERVER_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_EXIT_PORT_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_EXIT_SERVER_NAME_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_EXIT_PUBLIC_KEY_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_EXIT_SHORT_ID_FIELD_NUMBER,
        AppSettings.RELAY_MASQUE_URL_FIELD_NUMBER,
        AppSettings.RELAY_MASQUE_USE_HTTP2_FALLBACK_FIELD_NUMBER,
        AppSettings.RELAY_LOCAL_SOCKS_HOST_FIELD_NUMBER,
        AppSettings.RELAY_LOCAL_SOCKS_PORT_FIELD_NUMBER,
        AppSettings.RELAY_UDP_ENABLED_FIELD_NUMBER,
        AppSettings.RELAY_TCP_FALLBACK_ENABLED_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_ENTRY_PROFILE_ID_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_EXIT_PROFILE_ID_FIELD_NUMBER,
        AppSettings.RELAY_CHAIN_MIDDLE_PROFILE_IDS_FIELD_NUMBER,
        AppSettings.RELAY_TUIC_ZERO_RTT_FIELD_NUMBER,
        AppSettings.RELAY_TUIC_CONGESTION_CONTROL_FIELD_NUMBER,
        AppSettings.RELAY_SHADOWTLS_INNER_PROFILE_ID_FIELD_NUMBER,
        AppSettings.RELAY_NAIVE_PATH_FIELD_NUMBER,
        AppSettings.RELAY_MASQUE_CLOUDFLARE_GEOHASH_ENABLED_FIELD_NUMBER,
        AppSettings.RELAY_CLOUDFLARE_TUNNEL_MODE_FIELD_NUMBER,
        AppSettings.RELAY_CLOUDFLARE_PUBLISH_LOCAL_ORIGIN_URL_FIELD_NUMBER,
        AppSettings.RELAY_CLOUDFLARE_CREDENTIALS_REF_FIELD_NUMBER,
        AppSettings.RELAY_FINALMASK_TYPE_FIELD_NUMBER,
        AppSettings.RELAY_FINALMASK_HEADER_HEX_FIELD_NUMBER,
        AppSettings.RELAY_FINALMASK_TRAILER_HEX_FIELD_NUMBER,
        AppSettings.RELAY_FINALMASK_RAND_RANGE_FIELD_NUMBER,
        AppSettings.RELAY_FINALMASK_SUDOKU_SEED_FIELD_NUMBER,
        AppSettings.RELAY_FINALMASK_FRAGMENT_PACKETS_FIELD_NUMBER,
        AppSettings.RELAY_FINALMASK_FRAGMENT_MIN_BYTES_FIELD_NUMBER,
        AppSettings.RELAY_FINALMASK_FRAGMENT_MAX_BYTES_FIELD_NUMBER,
        AppSettings.RELAY_APPS_SCRIPT_SCRIPT_IDS_FIELD_NUMBER,
        AppSettings.RELAY_APPS_SCRIPT_GOOGLE_IP_FIELD_NUMBER,
        AppSettings.RELAY_APPS_SCRIPT_FRONT_DOMAIN_FIELD_NUMBER,
        AppSettings.RELAY_APPS_SCRIPT_VERIFY_SSL_FIELD_NUMBER,
        AppSettings.RELAY_APPS_SCRIPT_PARALLEL_RELAY_FIELD_NUMBER,
        AppSettings.RELAY_APPS_SCRIPT_SNI_HOSTS_FIELD_NUMBER,
        AppSettings.RELAY_APPS_SCRIPT_DIRECT_HOSTS_FIELD_NUMBER,
        AppSettings.RELAY_VLESS_TRANSPORT_FIELD_NUMBER,
        AppSettings.RELAY_XHTTP_PATH_FIELD_NUMBER,
        AppSettings.RELAY_XHTTP_HOST_FIELD_NUMBER,
        AppSettings.RELAY_XHTTP_MODE_FIELD_NUMBER,
        AppSettings.RELAY_MIERU_PROTOCOL_FIELD_NUMBER,
        AppSettings.RELAY_MIERU_MULTIPLEXING_FIELD_NUMBER,
        AppSettings.RELAY_MIERU_MTU_FIELD_NUMBER,
        AppSettings.RELAY_SSH_AUTH_TYPE_FIELD_NUMBER,
        AppSettings.RELAY_SSH_HOST_KEY_FINGERPRINT_FIELD_NUMBER,
        AppSettings.RELAY_SSH_STRICT_HOST_KEY_FIELD_NUMBER,
        AppSettings.RELAY_OUTBOUND_BIND_IP_FIELD_NUMBER,
    )
