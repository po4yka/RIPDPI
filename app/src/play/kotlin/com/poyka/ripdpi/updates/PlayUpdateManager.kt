package com.poyka.ripdpi.updates

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayUpdateManager
    @Inject
    constructor() : AppUpdateManager {
        override val channelInfo: UpdateChannelInfo =
            UpdateChannelInfo(
                channel = DistributionChannel.Play,
                canCheckInApp = false,
                canInstallInApp = false,
            )

        override suspend fun checkForUpdate(): UpdateCheckResult = UpdateCheckResult.ExternalAuthority

        override suspend fun install(update: AvailableAppUpdate): UpdateInstallResult =
            UpdateInstallResult.ExternalAuthority
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayUpdateModule {
    @Binds
    @Singleton
    abstract fun bindAppUpdateManager(manager: PlayUpdateManager): AppUpdateManager
}
