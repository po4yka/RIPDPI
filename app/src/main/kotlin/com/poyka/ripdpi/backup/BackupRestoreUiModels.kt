package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.backup.BackupPreview
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.backup.RestoreSelection

/**
 * One-shot effect surfaced after an export attempt; the screen renders it as a
 * snackbar and (for SHARE only) an optional follow-up share action, then clears it.
 */
sealed interface BackupExportEffect {
    /**
     * Export succeeded. [byteCount] is the number of JSON bytes written; [offerShare]
     * is `true` only for [BackupVariant.SHARE] (FULL backups are never offered an
     * inline share, to avoid spraying credentials through a share sheet).
     */
    data class Success(
        val variant: BackupVariant,
        val byteCount: Long,
        val offerShare: Boolean,
    ) : BackupExportEffect

    /** The destination write failed. The screen also deletes any partial file. */
    data object WriteFailed : BackupExportEffect

    /** The user cancelled the SAF picker (or document creation returned no Uri). */
    data object Cancelled : BackupExportEffect
}

/**
 * One-shot effect surfaced after a "share redacted backup" attempt. The screen
 * launches the share sheet on [Ready] and cleans up the temp cache file on every
 * terminal outcome.
 */
sealed interface BackupShareEffect {
    /** A fresh SHARE backup was written to the cache file and is ready to share. */
    data object Ready : BackupShareEffect

    /** Writing the temp SHARE backup failed; nothing is shared. */
    data object Failed : BackupShareEffect
}

/**
 * One-shot effect surfaced after an import / restore attempt.
 */
sealed interface BackupRestoreEffect {
    /**
     * The restore committed. The screen restarts the process (ProcessPhoenix) so
     * all DataStore / Room observers reinitialize against the restored state.
     */
    data object Restored : BackupRestoreEffect

    /** The chosen file's schema version is newer than this app supports. */
    data class UnsupportedVersion(
        val found: Int,
        val supported: Int,
    ) : BackupRestoreEffect

    /** The file was malformed or unreadable before live data changed. */
    data object Malformed : BackupRestoreEffect

    /** Restore and compensation both failed, so live-store integrity is not guaranteed. */
    data object IntegrityFailure : BackupRestoreEffect

    /** The encrypted archive could not be authenticated with the entered passphrase. */
    data object DecryptionFailed : BackupRestoreEffect

    /** The user picked a file but selected no categories to restore. */
    data object NothingSelected : BackupRestoreEffect
}

/**
 * One-shot effect surfaced after a confirmed "reset all settings" wipe completes.
 */
sealed interface BackupResetEffect {
    /**
     * The wipe committed. The screen restarts the process (ProcessPhoenix) so the
     * app comes back up clean, at onboarding.
     */
    data object Wiped : BackupResetEffect
}

/**
 * The stable, non-localized token the user must type to confirm a destructive reset.
 * It is deliberately NOT translated: a fixed token is unambiguous across locales and
 * keeps the typed-confirmation gate testable.
 */
const val ResetConfirmationToken: String = "RESET"

/**
 * Live preview of a chosen backup file, shown BEFORE any write. Carries the parsed
 * JSON so the subsequent restore re-uses the exact bytes the user previewed.
 */
data class BackupImportPreview(
    val json: String,
    val preview: BackupPreview,
    val selection: RestoreSelection,
    val encrypted: Boolean = false,
)

/** Immutable render state for the Backup & Restore screen. */
data class BackupRestoreUiState(
    val exporting: Boolean = false,
    val exportDisabledByPolicy: Boolean = false,
    val importing: Boolean = false,
    val restoring: Boolean = false,
    /** True while a fresh SHARE backup is being written to the cache for sharing. */
    val sharing: Boolean = false,
    /** Non-null while the import-preview sheet is visible. */
    val importPreview: BackupImportPreview? = null,
    /** True while an encrypted FULL archive is waiting for its passphrase. */
    val encryptedImportPending: Boolean = false,
    /** True while the typed-confirmation reset dialog is visible. */
    val resetDialogVisible: Boolean = false,
    /** The text the user has typed into the reset-confirmation field so far. */
    val resetConfirmationInput: String = "",
    /** True while the wipe is in flight (dialog buttons disabled). */
    val resetting: Boolean = false,
) {
    /**
     * `true` once the user has typed the exact, case-sensitive confirmation token.
     * The reset confirm action stays disabled until this flips to `true`.
     */
    val resetConfirmationMatches: Boolean
        get() = resetConfirmationInput == ResetConfirmationToken
}
