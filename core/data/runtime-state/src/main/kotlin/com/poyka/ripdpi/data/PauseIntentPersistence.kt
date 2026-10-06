package com.poyka.ripdpi.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface PauseClock {
    fun read(): PauseClockReading
}

interface PauseAuthorityPersistence {
    fun read(): PauseAuthorityState?

    fun commit(state: PauseAuthorityState)
}

/** Short, checked durable transitions only; this authority never invokes service/data callbacks. */

@Singleton class AndroidPauseClock
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PauseClock {
        override fun read() =
            PauseClockReading(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                runCatching {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
                }.getOrNull(),
            )
    }

@Singleton class CheckedPauseAuthorityPersistence
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : PauseAuthorityPersistence {
        private val preferences = context.getSharedPreferences("pause_intent_authority", Context.MODE_PRIVATE)

        override fun read(): PauseAuthorityState? =
            preferences.getString("state", null)?.let {
                RuntimePersistenceCodec.decode(it).let { (state, migrated) ->
                    if (migrated) commit(state)
                    state
                }
            }

        override fun commit(state: PauseAuthorityState) {
            check(preferences.edit().putString("state", RuntimePersistenceCodec.encode(state)).commit()) {
                "Pause intent persistence failed"
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PauseAuthorityModule {
    @Binds abstract fun persistence(value: CheckedPauseAuthorityPersistence): PauseAuthorityPersistence

    @Binds abstract fun clock(value: AndroidPauseClock): PauseClock
}
