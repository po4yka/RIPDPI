package com.poyka.ripdpi.data

import android.content.Context
import androidx.datastore.core.DataStore
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun snapshot(): AppSettings

    suspend fun update(transform: AppSettings.Builder.() -> Unit)

    suspend fun replace(settings: AppSettings)
}

@Singleton
class DefaultAppSettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<AppSettings>,
    ) : AppSettingsRepository {
        override val settings: Flow<AppSettings> = dataStore.data

        override suspend fun snapshot(): AppSettings = dataStore.data.first()

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            dataStore.updateData { current ->
                current.toBuilder().apply(transform).build()
            }
        }

        override suspend fun replace(settings: AppSettings) {
            dataStore.updateData { settings }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AppSettingsRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAppSettingsRepository(repository: DefaultAppSettingsRepository): AppSettingsRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AppSettingsDataStoreModule {
    @Provides
    @Singleton
    fun provideAppSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<AppSettings> = context.settingsStore
}
