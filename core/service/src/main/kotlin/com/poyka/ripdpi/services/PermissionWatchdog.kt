package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

data class PermissionChangeEvent(
    val kind: String,
    val detectedAt: Long,
) {
    companion object {
        const val KIND_NOTIFICATIONS = "notifications"
        const val KIND_VPN_CONSENT = "vpn_consent"
    }
}

interface PermissionWatchdog {
    val changes: SharedFlow<PermissionChangeEvent>
}

@Singleton
class DefaultPermissionWatchdog
    @Inject
    constructor(
        private val checker: RuntimePermissionChecker,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : PermissionWatchdog {
        private companion object {
            private const val PollIntervalMs = 30_000L
            private const val StopTimeoutMs = 5_000L
        }

        override val changes: SharedFlow<PermissionChangeEvent> =
            pollPermissionChanges(
                checker = checker,
                intervalMs = PollIntervalMs,
                clock = System::currentTimeMillis,
            ).shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = StopTimeoutMs),
                replay = 0,
            )
    }

internal fun pollPermissionChanges(
    checker: RuntimePermissionChecker,
    intervalMs: Long,
    clock: () -> Long,
): Flow<PermissionChangeEvent> =
    flow {
        var previous = checker.check()
        while (true) {
            delay(intervalMs)
            val current = checker.check()
            if (previous.notificationsGranted && !current.notificationsGranted) {
                emit(PermissionChangeEvent(PermissionChangeEvent.KIND_NOTIFICATIONS, clock()))
            }
            if (previous.vpnConsentGranted && !current.vpnConsentGranted) {
                emit(PermissionChangeEvent(PermissionChangeEvent.KIND_VPN_CONSENT, clock()))
            }
            previous = current
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionWatchdogModule {
    @Binds
    @Singleton
    abstract fun bindPermissionWatchdog(watchdog: DefaultPermissionWatchdog): PermissionWatchdog
}
