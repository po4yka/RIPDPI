package com.poyka.ripdpi.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface WidgetStateRepository {
    suspend fun write(snapshot: WidgetSnapshot)

    fun observe(): Flow<WidgetSnapshot>

    suspend fun snapshot(): WidgetSnapshot
}

private val Context.widgetDataStore by preferencesDataStore(name = "ripdpi_widget_state")

private val SNAPSHOT_KEY = stringPreferencesKey("snapshot")

@Singleton
class DefaultWidgetStateRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WidgetStateRepository {
        private val json = RipDpiJson

        override suspend fun write(snapshot: WidgetSnapshot) {
            context.widgetDataStore.edit { prefs ->
                prefs[SNAPSHOT_KEY] = json.encodeToString(WidgetSnapshot.serializer(), snapshot)
            }
        }

        override fun observe(): Flow<WidgetSnapshot> =
            context.widgetDataStore.data.map { prefs ->
                deserialize(prefs[SNAPSHOT_KEY])
            }

        override suspend fun snapshot(): WidgetSnapshot =
            deserialize(context.widgetDataStore.data.first()[SNAPSHOT_KEY])

        private fun deserialize(raw: String?): WidgetSnapshot =
            raw?.let {
                runCatching { json.decodeFromString(WidgetSnapshot.serializer(), it) }.getOrNull()
            } ?: WidgetSnapshot()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetStateModule {
    @Binds
    @Singleton
    abstract fun bindWidgetStateRepository(impl: DefaultWidgetStateRepository): WidgetStateRepository
}
