package com.poyka.ripdpi.services

import android.content.Context
import android.net.VpnService
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AndroidHardKillSwitchStatus {
    ENABLED,
    NOT_ENABLED,
    UNKNOWN,
}

data class AndroidHardKillSwitchSnapshot(
    val status: AndroidHardKillSwitchStatus = AndroidHardKillSwitchStatus.UNKNOWN,
    val alwaysOn: Boolean? = null,
    val lockdown: Boolean? = null,
    val updatedAtMillis: Long = 0L,
) {
    val isEnabled: Boolean
        get() = status == AndroidHardKillSwitchStatus.ENABLED
}

internal fun AndroidHardKillSwitchSnapshot.allowsServiceStop(action: String): Boolean =
    when (action) {
        // A notification is not the only way to recover on platforms where the
        // public VpnService lockdown flags are unavailable, so fail closed here.
        notificationStopAction -> status == AndroidHardKillSwitchStatus.NOT_ENABLED

        else -> status != AndroidHardKillSwitchStatus.ENABLED
    }

interface AndroidHardKillSwitchStateStore {
    val snapshot: StateFlow<AndroidHardKillSwitchSnapshot>

    fun update(snapshot: AndroidHardKillSwitchSnapshot)
}

@Singleton
class SharedPreferencesAndroidHardKillSwitchStateStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AndroidHardKillSwitchStateStore {
        private val preferences =
            context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        private val _snapshot = MutableStateFlow(readSnapshot())

        override val snapshot: StateFlow<AndroidHardKillSwitchSnapshot> = _snapshot.asStateFlow()

        override fun update(snapshot: AndroidHardKillSwitchSnapshot) {
            preferences
                .edit()
                .putString(KeyStatus, snapshot.status.name)
                .putBoolean(KeyHasAlwaysOn, snapshot.alwaysOn != null)
                .putBoolean(KeyAlwaysOn, snapshot.alwaysOn == true)
                .putBoolean(KeyHasLockdown, snapshot.lockdown != null)
                .putBoolean(KeyLockdown, snapshot.lockdown == true)
                .putLong(KeyUpdatedAt, snapshot.updatedAtMillis)
                .apply()
            _snapshot.value = snapshot
        }

        private fun readSnapshot(): AndroidHardKillSwitchSnapshot {
            val status =
                preferences
                    .getString(KeyStatus, null)
                    ?.let { runCatching { AndroidHardKillSwitchStatus.valueOf(it) }.getOrNull() }
                    ?: AndroidHardKillSwitchStatus.UNKNOWN
            return AndroidHardKillSwitchSnapshot(
                status = status,
                alwaysOn =
                    preferences
                        .getBoolean(KeyAlwaysOn, false)
                        .takeIf { preferences.getBoolean(KeyHasAlwaysOn, false) },
                lockdown =
                    preferences
                        .getBoolean(KeyLockdown, false)
                        .takeIf { preferences.getBoolean(KeyHasLockdown, false) },
                updatedAtMillis = preferences.getLong(KeyUpdatedAt, 0L),
            )
        }

        private companion object {
            const val PreferencesName = "android_hard_kill_switch_state"
            const val KeyStatus = "status"
            const val KeyHasAlwaysOn = "has_always_on"
            const val KeyAlwaysOn = "always_on"
            const val KeyHasLockdown = "has_lockdown"
            const val KeyLockdown = "lockdown"
            const val KeyUpdatedAt = "updated_at"
        }
    }

object AndroidHardKillSwitchStateReader {
    fun read(service: VpnService): AndroidHardKillSwitchSnapshot =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            unknown()
        } else {
            runCatching {
                fromPlatformFlags(
                    alwaysOn = service.isAlwaysOn,
                    lockdown = service.isLockdownEnabled,
                )
            }.getOrElse {
                unknown()
            }
        }

    fun fromPlatformFlags(
        alwaysOn: Boolean,
        lockdown: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): AndroidHardKillSwitchSnapshot =
        AndroidHardKillSwitchSnapshot(
            status =
                if (alwaysOn && lockdown) {
                    AndroidHardKillSwitchStatus.ENABLED
                } else {
                    AndroidHardKillSwitchStatus.NOT_ENABLED
                },
            alwaysOn = alwaysOn,
            lockdown = lockdown,
            updatedAtMillis = nowMillis,
        )

    fun unknown(nowMillis: Long = System.currentTimeMillis()): AndroidHardKillSwitchSnapshot =
        AndroidHardKillSwitchSnapshot(updatedAtMillis = nowMillis)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AndroidHardKillSwitchStateModule {
    @Binds
    @Singleton
    abstract fun bindAndroidHardKillSwitchStateStore(
        store: SharedPreferencesAndroidHardKillSwitchStateStore,
    ): AndroidHardKillSwitchStateStore
}
