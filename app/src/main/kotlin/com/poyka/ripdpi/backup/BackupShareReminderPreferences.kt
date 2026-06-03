package com.poyka.ripdpi.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the one-time "redacted SHARE backup is not zero-knowledge" reminder flag.
 *
 * Modelled on [com.poyka.ripdpi.ui.screens.detection.DetectionCheckPreferences]: a
 * small dedicated [android.content.SharedPreferences] store for a single UI
 * first-run flag, kept out of the protobuf-backed `AppSettings` (which is a config
 * contract) precisely because this is throwaway presentation state, not a setting
 * the user tunes or that needs to round-trip through backup export/import.
 */
@Singleton
class BackupShareReminderPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Returns `true` once the share-redaction reminder has been acknowledged. */
        fun wasReminderShown(): Boolean = prefs.getBoolean(PREF_REMINDER_SHOWN, false)

        /** Records that the share-redaction reminder has been shown and acknowledged. */
        fun markReminderShown() {
            prefs.edit().putBoolean(PREF_REMINDER_SHOWN, true).apply()
        }

        private companion object {
            const val PREFS_NAME = "backup_share_prefs"
            const val PREF_REMINDER_SHOWN = "share_redaction_reminder_shown"
        }
    }
