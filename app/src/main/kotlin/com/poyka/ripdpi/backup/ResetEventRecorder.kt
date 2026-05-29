package com.poyka.ripdpi.backup

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the one-shot "the user wiped this install" telemetry event so the next
 * session can distinguish a deliberate reset from a crash in user reports.
 *
 * The reset deletes every user-history store (profiles, groups, routes, settings,
 * diagnostics tables, caches). The event MUST therefore be persisted somewhere the
 * wipe explicitly does NOT clear, and it MUST survive the `ProcessPhoenix` restart
 * that follows the wipe — hence a dedicated, single-flag store rather than the
 * diagnostics telemetry table (which is wiped) or the `AppSettings` proto (which is
 * reset to defaults). The next session consumes the flag exactly once.
 */
interface ResetEventRecorder {
    /** Persists the pending "user_initiated_reset" event. Called BEFORE the wipe. */
    fun recordResetInitiated()

    /** `true` while a recorded reset event is still waiting to be consumed. */
    fun hasPendingResetEvent(): Boolean

    /**
     * Returns `true` exactly once if a reset event was pending, clearing it so a
     * later session does not re-report the same reset. Returns `false` otherwise.
     */
    fun consumeResetEvent(): Boolean
}

/**
 * Event name surfaced to diagnostics. Stable / non-localized: it is a telemetry key,
 * not user-facing copy.
 */
const val ResetEventName: String = "user_initiated_reset"

@Singleton
class SharedPreferencesResetEventRecorder
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ResetEventRecorder {
        // A dedicated prefs file, intentionally NOT in the reset's wipe set, so the
        // event row outlives the wipe and the subsequent process restart.
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun recordResetInitiated() {
            // commit() (not apply()) so the write is durable before the wipe and the
            // ProcessPhoenix-triggered process death that immediately follows.
            prefs
                .edit()
                .putBoolean(PREF_PENDING, true)
                .putString(PREF_EVENT_NAME, ResetEventName)
                .putLong(PREF_AT_EPOCH_MILLIS, System.currentTimeMillis())
                .commit()
        }

        override fun hasPendingResetEvent(): Boolean = prefs.getBoolean(PREF_PENDING, false)

        override fun consumeResetEvent(): Boolean {
            if (!prefs.getBoolean(PREF_PENDING, false)) return false
            prefs.edit().clear().apply()
            return true
        }

        private companion object {
            const val PREFS_NAME = "reset_event_prefs"
            const val PREF_PENDING = "reset_event_pending"
            const val PREF_EVENT_NAME = "reset_event_name"
            const val PREF_AT_EPOCH_MILLIS = "reset_event_at_epoch_millis"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ResetEventRecorderModule {
    @Binds
    @Singleton
    abstract fun bindResetEventRecorder(recorder: SharedPreferencesResetEventRecorder): ResetEventRecorder
}
