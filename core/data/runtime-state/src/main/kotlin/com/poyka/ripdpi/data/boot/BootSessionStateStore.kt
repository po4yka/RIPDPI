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
 * (direct-boot) storage where it can be read at `LOCKED_BOOT_COMPLETED`, before
 * the user unlocks. The secret-bearing profile bean stays in credential-encrypted
 * storage and is re-materialized after unlock via the existing supervisor reload
 * path. See `.claude/rules/network-fingerprint-privacy.md` and the
 * "Boot autostart and session persistence" epic.
 */
data class BootSessionPointer(
    val profileId: String,
    val mode: Mode,
)

/**
 * Persists the [BootSessionPointer] in direct-boot-aware (device-protected)
 * storage so a boot receiver can resume the previously-active service before the
 * user unlocks the device.
 *
 * The backing store is PLAIN (not [androidx.security.crypto.EncryptedSharedPreferences]):
 * the Android keystore master key is itself credential-encrypted and unavailable
 * during direct boot, so an encrypted store would fail to open at
 * `LOCKED_BOOT_COMPLETED`. The pointer holds no secrets, so plaintext on the
 * device-protected partition is the correct trade-off.
 */
interface BootSessionStateStore {
    /** The last recorded active-session pointer, or `null` when none was recorded. */
    fun lastSession(): BootSessionPointer?

    /** Records the active-session pointer (non-secret: profile id + service mode). */
    fun recordSession(
        profileId: String,
        mode: Mode,
    )

    /** Clears the recorded pointer — e.g. when the referenced profile no longer exists. */
    fun clear()

    /**
     * Whether a session was running at the moment the app process was last torn
     * down for reasons OTHER than an explicit user stop (e.g. an app update).
     * Gates the `MY_PACKAGE_REPLACED` auto-restart so a deliberately-stopped
     * tunnel is not silently resumed after an update. Defaults to `false`.
     */
    fun wasRunningAtUpdate(): Boolean

    /** Sets the [wasRunningAtUpdate] flag. */
    fun setWasRunningAtUpdate(value: Boolean)
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
            val rawMode = preferences.getString(KeyMode, null) ?: return null
            val mode = runCatching { Mode.fromString(rawMode) }.getOrNull() ?: return null
            val profileId = preferences.getString(KeyProfileId, "").orEmpty()
            return BootSessionPointer(profileId = profileId, mode = mode)
        }

        override fun recordSession(
            profileId: String,
            mode: Mode,
        ) {
            preferences
                .edit()
                .putString(KeyProfileId, profileId)
                .putString(KeyMode, mode.preferenceValue)
                .apply()
        }

        override fun clear() {
            preferences
                .edit()
                .remove(KeyProfileId)
                .remove(KeyMode)
                .apply()
        }

        override fun wasRunningAtUpdate(): Boolean = preferences.getBoolean(KeyWasRunningAtUpdate, false)

        override fun setWasRunningAtUpdate(value: Boolean) {
            preferences.edit().putBoolean(KeyWasRunningAtUpdate, value).apply()
        }

        private companion object {
            const val KeyProfileId = "boot-session-profile-id"
            const val KeyMode = "boot-session-mode"
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
