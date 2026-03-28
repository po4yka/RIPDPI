package com.poyka.ripdpi.permissions

import com.poyka.ripdpi.services.RuntimePermissionChecker
import com.poyka.ripdpi.services.RuntimePermissionState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidRuntimePermissionChecker
    @Inject
    constructor(
        private val permissionStatusProvider: PermissionStatusProvider,
    ) : RuntimePermissionChecker {
        override fun check(): RuntimePermissionState {
            val snapshot = permissionStatusProvider.currentSnapshot()
            return RuntimePermissionState(
                notificationsGranted = snapshot.notifications == PermissionStatus.Granted,
                vpnConsentGranted = snapshot.vpnConsent == PermissionStatus.Granted,
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimePermissionCheckerModule {
    @Binds
    @Singleton
    abstract fun bindRuntimePermissionChecker(checker: AndroidRuntimePermissionChecker): RuntimePermissionChecker
}
