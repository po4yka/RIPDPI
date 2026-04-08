package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

interface VpnAppExclusionPolicy {
    fun shouldExcludeOwnPackage(): Boolean

    fun russianAppsToExclude(): List<String>
}

@Singleton
class DefaultVpnAppExclusionPolicy
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) : VpnAppExclusionPolicy {
        override fun shouldExcludeOwnPackage(): Boolean = true

        override fun russianAppsToExclude(): List<String> {
            val settings = runBlocking { appSettingsRepository.snapshot() }
            if (settings.fullTunnelMode) return emptyList()
            return if (settings.excludeRussianAppsEnabled) RUSSIAN_APP_PACKAGES else emptyList()
        }

        companion object {
            val RUSSIAN_APP_PACKAGES: List<String> =
                listOf(
                    "com.vkontakte.android",
                    "com.vk.vkvideo",
                    "ru.ozon.app.android",
                    "com.wildberries.ru",
                    "ru.rostel",
                    "ru.vk.store",
                    "ru.mts.mtstv",
                    "ru.kinopoisk.tv",
                    "ru.ivi.client",
                    "ru.yandex.taxi",
                    "com.yandex.browser",
                    "ru.yandex.yandexmaps",
                    "ru.sberbankmobile",
                    "com.idamob.tinkoff.android",
                    "ru.alfabank.mobile.android",
                    "ru.mail.mailapp",
                    "com.avito.android",
                    "ru.yandex.music",
                    "ru.dublgis.dgismobile",
                    "ru.mts.mymts",
                    "ru.megafon.mlk",
                    "ru.beeline.services",
                    "ru.tele2.mytele2",
                    "ru.beru.android",
                    "ru.instamart",
                    "ru.sbcs.store",
                    "com.yandex.lavka",
                    "ru.zen.android",
                    "ru.foodfox.client",
                    "com.octopod.russianpost.client.android",
                    "com.logistic.sdek",
                )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnAppExclusionPolicyModule {
    @Binds
    @Singleton
    abstract fun bindVpnAppExclusionPolicy(policy: DefaultVpnAppExclusionPolicy): VpnAppExclusionPolicy
}
