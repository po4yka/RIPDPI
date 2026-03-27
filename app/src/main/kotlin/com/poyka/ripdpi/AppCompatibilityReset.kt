package com.poyka.ripdpi

import android.content.Context
import com.poyka.ripdpi.core.clearHostAutolearnStore
import com.poyka.ripdpi.data.resolveAppSettingsStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val CompatibilityResetPreferencesName = "compatibility_reset"
private const val CompatibilityResetAppliedKey = "pre_release_reset_v1_applied"
private const val DiagnosticsDatabaseName = "diagnostics.db"
private const val LegacyHostAutolearnDirectoryName = "ripdpi"
private const val LegacyHostAutolearnFileName = "host-autolearn-v1.json"

@Singleton
class AppCompatibilityReset
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun resetIfNeeded() {
            val preferences = context.getSharedPreferences(CompatibilityResetPreferencesName, Context.MODE_PRIVATE)
            if (preferences.getBoolean(CompatibilityResetAppliedKey, false)) {
                return
            }

            resolveAppSettingsStoreFile(context).let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
            context.deleteDatabase(DiagnosticsDatabaseName)
            clearHostAutolearnStore(context)
            resolveLegacyHostAutolearnStoreFile(context).let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }

            preferences.edit().putBoolean(CompatibilityResetAppliedKey, true).commit()
        }
    }

private fun resolveLegacyHostAutolearnStoreFile(context: Context): File =
    File(context.noBackupFilesDir, LegacyHostAutolearnDirectoryName).resolve(LegacyHostAutolearnFileName)
