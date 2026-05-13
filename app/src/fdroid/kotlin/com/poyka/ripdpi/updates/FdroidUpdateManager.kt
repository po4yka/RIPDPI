package com.poyka.ripdpi.updates

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FdroidUpdateManager
    @Inject
    constructor() : AppUpdateManager {
        override val channelInfo: UpdateChannelInfo =
            UpdateChannelInfo(
                channel = DistributionChannel.Fdroid,
                canCheckInApp = false,
                canInstallInApp = false,
            )

        override suspend fun checkForUpdate(): UpdateCheckResult = UpdateCheckResult.ExternalAuthority

        override suspend fun install(update: AvailableAppUpdate): UpdateInstallResult =
            UpdateInstallResult.ExternalAuthority
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class FdroidUpdateModule {
    @Binds
    @Singleton
    abstract fun bindAppUpdateManager(manager: FdroidUpdateManager): AppUpdateManager
}
