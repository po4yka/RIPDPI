package com.poyka.ripdpi.seed

import android.content.Context
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.RestoreResult
import com.poyka.ripdpi.data.backup.RestoreSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

internal const val PresetAssetName = "preset.json"
internal const val PresetPrefsName = "preset_state"
private const val PresetKeyApplied = "applied"

sealed interface PresetOutcome {
    data object Skipped : PresetOutcome

    data object Applied : PresetOutcome

    /** [reason] never carries preset contents. */
    data class Rejected(
        val reason: String,
    ) : PresetOutcome
}

/**
 * Restores the optional packaged preset (a plaintext FULL backup document) once, on a
 * fresh install. A build without the asset, an install that completed onboarding, and
 * an install that already applied a preset are left untouched.
 */
@Singleton
open class PresetApplier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settingsRepository: AppSettingsRepository,
        private val backupRestore: BackupRestoreUseCase,
    ) {
        // Lives outside AppSettings because the restore replaces AppSettings.
        private val prefs by lazy { context.getSharedPreferences(PresetPrefsName, Context.MODE_PRIVATE) }

        suspend fun applyIfFresh(): PresetOutcome {
            val fresh = !prefs.getBoolean(PresetKeyApplied, false) && !settingsRepository.snapshot().onboardingComplete
            val json = if (fresh) readPreset() else null
            return if (json == null) PresetOutcome.Skipped else restore(json)
        }

        private suspend fun restore(json: String): PresetOutcome {
            val result =
                try {
                    backupRestore.restore(json, RestoreSelection(true, true, true))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    // The message can quote the document, so only the type leaves this class.
                    return PresetOutcome.Rejected("unexpected ${error::class.simpleName}")
                }
            return when (result) {
                is RestoreResult.Success -> {
                    check(prefs.edit().putBoolean(PresetKeyApplied, true).commit()) {
                        "Failed to persist preset state"
                    }
                    PresetOutcome.Applied
                }

                is RestoreResult.UnsupportedVersion -> {
                    PresetOutcome.Rejected("unsupported backup version ${result.found}")
                }

                is RestoreResult.Aborted -> {
                    PresetOutcome.Rejected("restore aborted: ${result.reason}")
                }

                RestoreResult.NothingSelected -> {
                    PresetOutcome.Rejected("nothing selected")
                }
            }
        }

        protected open fun readPreset(): String? =
            try {
                context.assets
                    .open(PresetAssetName)
                    .use { it.bufferedReader(Charsets.UTF_8).readText() }
                    .takeIf(String::isNotBlank)
            } catch (_: IOException) {
                null
            }
    }
