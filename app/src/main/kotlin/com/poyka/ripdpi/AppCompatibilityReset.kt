package com.poyka.ripdpi

import android.content.Context
import androidx.core.content.edit
import com.poyka.ripdpi.data.resolveAppSettingsStoreFile
import com.poyka.ripdpi.services.HostAutolearnStoreController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val CompatibilityResetPreferencesName = "compatibility_reset"
private const val CompatibilityResetAppliedKey = "pre_release_reset_v1_applied"
private const val DiagnosticsDatabaseName = "diagnostics.db"
private const val LegacyHostAutolearnDirectoryName = "ripdpi"
private const val LegacyHostAutolearnFileName = "host-autolearn-v1.json"

interface AppCompatibilityResetter {
    fun resetIfNeeded()
}

@Singleton
class AppCompatibilityReset
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val hostAutolearnStoreController: HostAutolearnStoreController,
    ) : AppCompatibilityResetter {
        override fun resetIfNeeded() {
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
            hostAutolearnStoreController.clearStore()
            resolveLegacyHostAutolearnStoreFile(context).let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }

            preferences.edit { putBoolean(CompatibilityResetAppliedKey, true) }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AppCompatibilityResetBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAppCompatibilityResetter(resetter: AppCompatibilityReset): AppCompatibilityResetter
}

private fun resolveLegacyHostAutolearnStoreFile(context: Context): File =
    File(context.noBackupFilesDir, LegacyHostAutolearnDirectoryName).resolve(LegacyHostAutolearnFileName)
