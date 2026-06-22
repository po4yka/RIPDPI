package com.poyka.ripdpi.data.awg

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-module selection signal for the standalone AmneziaWG provider: whether a
 * standalone AmneziaWG profile is the active VPN data plane and, if so, which
 * durable profile id backs it.
 *
 * This is the small, secret-free signal `:core:service` reads at VPN startup to
 * decide whether to branch onto the standalone AmneziaWG runtime instead of the
 * native proxy/WARP/relay stack. It carries NO key material — only a profile-id
 * pointer into the `AwgProfileRepository` (`AwgCredentialStore` holds the
 * secrets). Mirrors the `XrayProviderSelectionRecord` precedent.
 */
@Serializable
data class AwgSelectionRecord(
    val active: Boolean = false,
    /** Durable AmneziaWG profile id (an `awg-<UUID>`) when [active]; else blank. */
    val activeProfileId: String = "",
)

/**
 * Durable, cross-module read/write surface for the standalone AmneziaWG
 * selection.
 *
 * Persisted on every selection change (event-driven, not timer-driven) per
 * `.claude/rules/android-vpn-lifecycle.md` so it survives an LMK `SIGKILL` and a
 * cold / boot resume reconstructs the AmneziaWG session rather than the default
 * native path. The `:app` connect flow writes it; `:core:service` reads
 * [current] at VPN startup.
 */
interface AwgProviderSelectionStore {
    suspend fun current(): AwgSelectionRecord

    suspend fun update(record: AwgSelectionRecord)
}

@Singleton
class SharedPreferencesAwgProviderSelectionStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AwgProviderSelectionStore {
        private val preferences = context.getSharedPreferences(SelectionPrefsName, Context.MODE_PRIVATE)
        private val json = RipDpiJson

        override suspend fun current(): AwgSelectionRecord =
            preferences.getString(SelectionKey, null)?.let {
                runCatching { json.decodeFromString(AwgSelectionRecord.serializer(), it) }.getOrNull()
            } ?: AwgSelectionRecord()

        override suspend fun update(record: AwgSelectionRecord) {
            preferences
                .edit()
                .putString(SelectionKey, json.encodeToString(AwgSelectionRecord.serializer(), record))
                .apply()
        }

        private companion object {
            const val SelectionPrefsName = "awg_provider_selection"
            const val SelectionKey = "selection"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AwgProviderSelectionStoreModule {
    @Binds
    @Singleton
    abstract fun bindAwgProviderSelectionStore(
        store: SharedPreferencesAwgProviderSelectionStore,
    ): AwgProviderSelectionStore
}
