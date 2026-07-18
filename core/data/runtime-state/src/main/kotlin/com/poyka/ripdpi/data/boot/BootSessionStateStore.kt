package com.poyka.ripdpi.data.boot

import android.content.Context
import android.content.SharedPreferences
import com.poyka.ripdpi.data.Mode
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Non-secret pointer to the last-active RIPDPI session: the active profile id
 * plus the service [Mode] that was running.
 *
 * Intentionally carries NO secret material — only a stable profile id and the
 * coarse service mode — so it is safe to persist in device-protected
 * storage so it survives reboot independently of credential-encrypted app state.
 * The secret-bearing profile bean stays in credential-encrypted storage, so actual
 * resume waits for `BOOT_COMPLETED` after unlock. See
 * `.claude/rules/network-fingerprint-privacy.md` and the
 * "Boot autostart and session persistence" epic.
 */
data class BootSessionPointer(
    val profileId: String,
    val mode: Mode,
)

/**
 * Persists the [BootSessionPointer] in device-protected storage so it survives
 * reboot and remains independent from the credential-encrypted profile database.
 *
 * The backing store is PLAIN (not [androidx.security.crypto.EncryptedSharedPreferences]):
 * the Android keystore master key is itself credential-encrypted and unavailable
 * during direct boot, so an encrypted store would fail to open at
 * `LOCKED_BOOT_COMPLETED`. The pointer holds no secrets, so plaintext on the
 * device-protected partition is the correct trade-off. Service resume still waits
 * for credential unlock because the complete profile configuration remains protected.
 */
interface BootSessionStateStore {
    /** The last recorded active-session pointer, or `null` when none was recorded. */
    fun lastSession(): BootSessionPointer?

    /** Records the active-session pointer (non-secret: profile id + service mode). */
    fun recordSession(
        profileId: String,
        mode: Mode,
    )

    /** Stable, non-secret standalone AWG profile id selected for VPN egress. */
    fun activeAwgProfileId(): String? = null

    /** Persists or clears the standalone AWG selection without storing profile secrets. */
    fun setActiveAwgProfileId(profileId: String?) = Unit

    /** Clears the recorded pointer — e.g. when the referenced profile no longer exists. */
    fun clear()

    /**
     * Whether the durable session should still be running after process loss.
     *
     * Explicit user stop clears the marker. Unexpected process death and package
     * replacement retain it so either recovery path can reconstruct the service.
     * Defaults to `false`.
     */
    fun wasRunningAtUpdate(): Boolean

    /** Sets the durable desired-running marker returned by [wasRunningAtUpdate]. */
    fun setWasRunningAtUpdate(value: Boolean)

    /** Clears both the session pointer and every auto-resume marker. */
    fun clearAll() {
        clear()
        setWasRunningAtUpdate(false)
    }
}

/**
 * Hilt qualifier for the device-protected [SharedPreferences] used by the boot
 * subsystem. Distinct from the default credential-encrypted prefs so callers
 * cannot accidentally write secrets into the direct-boot partition.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceProtectedBootPrefs

@Singleton
class SharedPreferencesBootSessionStateStore
    @Inject
    constructor(
        @param:DeviceProtectedBootPrefs private val preferences: SharedPreferences,
    ) : BootSessionStateStore {
        override fun lastSession(): BootSessionPointer? {
            val mode =
                preferences
                    .getString(KeyMode, null)
                    ?.let { rawMode -> runCatching { Mode.fromString(rawMode) }.getOrNull() }
                    ?: return null
            val profileId = preferences.getString(KeyProfileId, "").orEmpty()
            return BootSessionPointer(profileId = profileId, mode = mode)
        }

        override fun recordSession(
            profileId: String,
            mode: Mode,
        ) {
            // commit() (synchronous, fsync-backed), NOT apply(): an LMK SIGKILL can
            // arrive at any moment with no Drop, flushing nothing. apply() only
            // schedules the disk write, so the pointer could be lost before it lands
            // and the boot receiver would have nothing to resume. See
            // `.claude/rules/android-vpn-lifecycle.md` (state-persistence rule).
            preferences
                .edit()
                .putString(KeyProfileId, profileId)
                .putString(KeyMode, mode.preferenceValue)
                .commit()
        }

        override fun activeAwgProfileId(): String? =
            preferences.getString(KeyActiveAwgProfileId, null)?.takeIf(String::isNotBlank)

        override fun setActiveAwgProfileId(profileId: String?) {
            preferences
                .edit()
                .also { editor ->
                    if (profileId.isNullOrBlank()) {
                        editor.remove(KeyActiveAwgProfileId)
                    } else {
                        editor.putString(KeyActiveAwgProfileId, profileId)
                    }
                }.commit()
        }

        override fun clear() {
            preferences
                .edit()
                .remove(KeyProfileId)
                .remove(KeyMode)
                .commit()
        }

        override fun wasRunningAtUpdate(): Boolean = preferences.getBoolean(KeyWasRunningAtUpdate, false)

        override fun setWasRunningAtUpdate(value: Boolean) {
            // Both edges are durability-critical. A lost `true` prevents recovery
            // after LMK/SIGKILL; a lost `false` can resurrect a tunnel that the user
            // explicitly stopped if the process dies before an asynchronous apply.
            preferences.edit().putBoolean(KeyWasRunningAtUpdate, value).commit()
        }

        override fun clearAll() {
            preferences.edit().clear().commit()
        }

        private companion object {
            const val KeyProfileId = "boot-session-profile-id"
            const val KeyMode = "boot-session-mode"
            const val KeyActiveAwgProfileId = "boot-session-active-awg-profile-id"
            const val KeyWasRunningAtUpdate = "boot-session-was-running-at-update"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object DeviceProtectedBootPrefsModule {
    @Provides
    @Singleton
    @DeviceProtectedBootPrefs
    fun provideDeviceProtectedBootPrefs(
        @ApplicationContext context: Context,
    ): SharedPreferences =
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    private const val PrefsName = "ripdpi_boot_session_state"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BootSessionStateStoreModule {
    @Binds
    @Singleton
    abstract fun bindBootSessionStateStore(store: SharedPreferencesBootSessionStateStore): BootSessionStateStore
}
