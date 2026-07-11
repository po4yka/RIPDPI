package com.poyka.ripdpi.backup

import android.util.Log
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.proto.AppSettings
import javax.inject.Inject

private const val LogTag = "ResetAllSettings"

/**
 * Wipes every user-controlled store back to a clean install, then leaves it to the
 * caller to restart the process (via `ProcessPhoenix`) so the app reinitializes into
 * onboarding.
 *
 * **Wipes** (in order, telemetry first):
 * 1. Records the one-shot `user_initiated_reset` event BEFORE any deletion so it
 *    survives the wipe and the restart ([ResetEventRecorder] persists to a dedicated
 *    store that is intentionally NOT in the wipe set).
 * 2. Proxy groups and their embedded member profiles ([ProxyGroupRepository.replaceAll]
 *    with an empty list).
 * 3. Routing rules ([RuleDao.deleteAll]).
 * 4. User settings ([AppSettingsRepository.replace] with the proto default instance).
 * 5. Diagnostics user history ([DiagnosticsHistoryResetStore.clearRuntimeHistory] —
 *    scans, telemetry samples, native events, snapshots, remembered policies, etc.;
 *    the diagnostic-profile catalog and pack versions are NOT user history and stay).
 * 6. Cache directories ([CacheDirectoryCleaner.clearCaches]).
 *
 * **Keeps** (out of scope — never touched here): the app install itself, keystore
 * entries needed to bootstrap the next session, and OS-granted permissions. None of
 * those are reachable from these stores, so they survive by construction.
 *
 * The reset is invoked only after the typed-confirmation gate in the UI; there is no
 * additional confirmation here. This use case has no cancel point — the UI owns
 * cancellation, and nothing here writes until [reset] is called.
 */
class ResetAllSettingsUseCase
    @Inject
    constructor(
        private val resetEventRecorder: ResetEventRecorder,
        private val groupRepository: ProxyGroupRepository,
        private val ruleDao: RuleDao,
        private val settingsRepository: AppSettingsRepository,
        private val diagnosticsHistoryResetStore: DiagnosticsHistoryResetStore,
        private val cacheDirectoryCleaner: CacheDirectoryCleaner,
    ) {
        /**
         * Performs the full wipe. Telemetry is recorded first; each subsequent store
         * is wiped independently. On success the caller restarts the process.
         */
        suspend fun reset() {
            // 1. Telemetry BEFORE the wipe, to a store outside the wipe set.
            resetEventRecorder.recordResetInitiated()

            // 2. Groups + embedded profiles (covers ProxyEntity / ProxyGroup / Subscription).
            groupRepository.replaceAll(emptyList())

            // 3. Routing rules.
            ruleDao.deleteAll()

            // 4. User settings back to defaults.
            settingsRepository.replace(AppSettings.getDefaultInstance())

            // 5. Diagnostics user-history tables (catalog/pack versions retained).
            diagnosticsHistoryResetStore.clearRuntimeHistory()

            // 6. Cache directories.
            cacheDirectoryCleaner.clearCaches()

            Log.i(LogTag, "Reset complete: all user stores wiped; restart pending")
        }
    }
