package com.poyka.ripdpi.backup

import android.util.Log
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Resets database state atomically, then clears session history and user-owned files. */
class ResetAllSettingsUseCase
    @Inject
    constructor(
        private val resetEventRecorder: ResetEventRecorder,
        private val settingsRepository: AppSettingsRepository,
        private val profileMutations: ProfileMutationCoordinator,
        private val userSessionResetStore: UserSessionResetStore,
        private val diagnosticsHistoryResetStore: DiagnosticsHistoryResetStore,
        private val userArtifactResetStore: UserArtifactResetStore,
        private val cacheDirectoryCleaner: CacheDirectoryCleaner,
    ) {
        suspend fun reset() =
            withContext(NonCancellable) {
                // An explicit reset must also work when legacy settings cannot be imported.
                val settingsBeforeReset =
                    runCatching { settingsRepository.snapshot() }
                        .getOrDefault(AppSettingsSerializer.defaultValue)
                resetEventRecorder.recordResetInitiated()
                profileMutations.runReset {
                    userSessionResetStore.clearAll()
                    diagnosticsHistoryResetStore.clearRuntimeHistory()
                    userArtifactResetStore.clearAll(settingsBeforeReset)
                    cacheDirectoryCleaner.clearCaches()
                }
                Log.i("ResetAllSettings", "Reset complete: all user stores wiped; restart pending")
            }
    }
