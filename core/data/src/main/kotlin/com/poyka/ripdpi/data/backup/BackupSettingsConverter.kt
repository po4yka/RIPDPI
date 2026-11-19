package com.poyka.ripdpi.data.backup

import com.google.protobuf.InvalidProtocolBufferException
import com.poyka.ripdpi.data.appSettingsFromJson
import com.poyka.ripdpi.data.validatedForBackupRestore
import com.poyka.ripdpi.proto.AppSettings
import java.util.Base64

/** FULL backups carry the portable settings protobuf, including optional and repeated fields. */
object BackupSettingsConverter {
    const val SnapshotKey: String = "app_settings.protobuf.v1"
    internal const val LegacySnapshotKey: String = "app_settings.snapshot_json.v1"

    fun toMap(
        settings: AppSettings,
        variant: BackupVariant,
    ): Map<String, String> =
        when (variant) {
            BackupVariant.FULL -> {
                mapOf(SnapshotKey to Base64.getEncoder().encodeToString(settings.portable().toByteArray()))
            }

            BackupVariant.SHARE -> {
                emptyMap()
            }
        }

    /** A missing settings section leaves the live settings untouched. */
    fun fromMap(map: Map<String, String>): AppSettings? {
        val payload = map[SnapshotKey] ?: return map[LegacySnapshotKey]?.let(::appSettingsFromJson)
        val settings =
            try {
                AppSettings.parseFrom(Base64.getDecoder().decode(payload))
            } catch (error: InvalidProtocolBufferException) {
                throw IllegalArgumentException("Invalid backup settings", error)
            }
        return settings.portable()
    }

    private fun AppSettings.portable(): AppSettings =
        validatedForBackupRestore()
            .toBuilder()
            .apply { if (!this@portable.hasRelayDnsOverTunnelEnabled()) clearRelayDnsOverTunnelEnabled() }
            // Device credentials and the notice about losing the Keystore key belong to this installation.
            .clearBackupPin()
            .clearProxyLanAuthToken()
            .clearAppLockResetNoticePending()
            // Privileged mode, traffic recording and field reports require a local opt-in.
            .clearRootModeEnabled()
            .clearFieldReportEnabled()
            .clearPcapCaptureEnabled()
            .clearDetectionDiagnosticTlsKeylogPath()
            // Restoring a preference does not restore downloaded GeoIP or GeoSite files.
            .clearGeoAssetGeoipVersionTag()
            .clearGeoAssetGeositeVersionTag()
            .clearGeoAssetLastUpdatedEpochMillis()
            .build()
}
