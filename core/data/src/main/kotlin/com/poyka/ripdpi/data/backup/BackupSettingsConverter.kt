package com.poyka.ripdpi.data.backup

import com.poyka.ripdpi.data.appSettingsFromJson
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.proto.AppSettings

/**
 * Bridges the live [AppSettings] proto and the backup document's
 * `settings: Map<String, String>` field.
 *
 * The proto carries ~300 fields including repeated and nested-message values, so a
 * field-per-entry flat map would be lossy and brittle for FULL exports. Instead we reuse the
 * existing, lossless [AppSettings.toJson] / [appSettingsFromJson] snapshot codec
 * and store its output under a single, versioned map key. The map shape is kept
 * (rather than a raw string) so a future schema can add sibling keys without changing the `BackupV1` wire type.
 */
object BackupSettingsConverter {
    /**
     * Map key holding the lossless JSON snapshot of [AppSettings]. Versioned so a
     * later format can introduce a `v2` codec without colliding.
     */
    const val SnapshotKey: String = "app_settings.snapshot_json.v1"

    /**
     * Projects [settings] into the backup `settings` map for [variant].
     *
     * SHARE exports intentionally omit settings until settings fields have their own audited allowlist. The live proto includes endpoints, LAN auth tokens, DNS URLs, and diagnostic paths, so carrying the opaque snapshot would make a SHARE backup equivalent to a FULL settings export.
     */
    fun toMap(
        settings: AppSettings,
        variant: BackupVariant,
    ): Map<String, String> =
        when (variant) {
            BackupVariant.FULL -> mapOf(SnapshotKey to settings.toJson())
            BackupVariant.SHARE -> emptyMap()
        }

    /**
     * Reconstructs an [AppSettings] from a backup `settings` map.
     *
     * Returns the proto default instance when the snapshot key is absent (e.g. an
     * older or partial backup) rather than throwing, so a restore degrades to
     * "settings unchanged" instead of failing the whole import.
     */
    fun fromMap(map: Map<String, String>): AppSettings {
        val snapshot = map[SnapshotKey] ?: return AppSettings.getDefaultInstance()
        return appSettingsFromJson(snapshot)
    }
}
