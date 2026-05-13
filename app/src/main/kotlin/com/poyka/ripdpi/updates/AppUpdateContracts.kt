package com.poyka.ripdpi.updates

import android.content.Intent

enum class DistributionChannel {
    Play,
    Fdroid,
    Github,
}

data class UpdateChannelInfo(
    val channel: DistributionChannel,
    val canCheckInApp: Boolean,
    val canInstallInApp: Boolean,
)

data class AvailableAppUpdate(
    val versionCode: Long,
    val versionName: String,
    val changelog: String,
    val sizeBytes: Long?,
    val artifactName: String? = null,
)

interface AppUpdateManager {
    val channelInfo: UpdateChannelInfo

    suspend fun checkForUpdate(): UpdateCheckResult

    suspend fun install(update: AvailableAppUpdate): UpdateInstallResult
}

sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult

    data object ExternalAuthority : UpdateCheckResult

    data class Available(
        val update: AvailableAppUpdate,
    ) : UpdateCheckResult

    data class Failed(
        val reason: UpdateFailureReason,
    ) : UpdateCheckResult
}

sealed interface UpdateInstallResult {
    data object ExternalAuthority : UpdateInstallResult

    data object Started : UpdateInstallResult

    data class PermissionRequired(
        val intent: Intent,
    ) : UpdateInstallResult

    data class Failed(
        val reason: UpdateFailureReason,
    ) : UpdateInstallResult
}

enum class UpdateFailureReason {
    Network,
    InvalidMetadata,
    InvalidRepositoryAsset,
    InvalidPackageName,
    InvalidVersionCode,
    UnsupportedSdk,
    ChecksumMismatch,
    PackageReadFailed,
    InstallerUnavailable,
    NotAvailable,
    Unknown,
}

data class UpdateUiState(
    val channelInfo: UpdateChannelInfo,
    val status: UpdateUiStatus = UpdateUiStatus.Idle,
    val availableUpdate: AvailableAppUpdate? = null,
)

sealed interface UpdateUiStatus {
    data object Idle : UpdateUiStatus

    data object Checking : UpdateUiStatus

    data object Downloading : UpdateUiStatus

    data object ExternalAuthority : UpdateUiStatus

    data object NoUpdate : UpdateUiStatus

    data object InstallerStarted : UpdateUiStatus

    data object PermissionRequired : UpdateUiStatus

    data class Failed(
        val reason: UpdateFailureReason,
    ) : UpdateUiStatus
}

sealed interface UpdateEffect {
    data class OpenIntent(
        val intent: Intent,
    ) : UpdateEffect
}
