package com.poyka.ripdpi.data.backup

import com.poyka.ripdpi.data.appSettingsFromJson
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.proto.AppSettings

/**
 * Bridges the live [AppSettings] proto and the backup document's
 * `settings: Map<String, String>` field.
 *
 * The proto carries ~300 fields including repeated and nested-message values, so a
 * field-per-entry flat map would be lossy and brittle. Instead we reuse the
 * existing, lossless [AppSettings.toJson] / [appSettingsFromJson] snapshot codec
 * and store its output under a single, versioned map key. The map shape is kept
 * (rather than a raw string) so a future schema can add sibling keys without
 * changing the `BackupV1` wire type, and so SHARE/FULL behaviour can later redact
 * individual settings keys if needed.
 */
object BackupSettingsConverter {
    /**
     * Map key holding the lossless JSON snapshot of [AppSettings]. Versioned so a
     * later format can introduce a `v2` codec without colliding.
     */
    const val SnapshotKey: String = "app_settings.snapshot_json.v1"

    /** Projects [settings] into the backup `settings` map. */
    fun toMap(settings: AppSettings): Map<String, String> = mapOf(SnapshotKey to settings.toJson())

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
