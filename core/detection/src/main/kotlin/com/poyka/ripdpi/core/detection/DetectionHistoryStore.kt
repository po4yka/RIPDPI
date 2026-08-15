package com.poyka.ripdpi.core.detection

import android.content.Context
import androidx.core.content.edit
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DetectionHistoryEntry(
    val networkFingerprint: String,
    val networkSummary: String,
    val timestamp: Long,
    val verdict: String,
    val stealthScore: Int,
    val evidenceCount: Int,
)

@Serializable
data class DetectionHistory(
    val entries: List<DetectionHistoryEntry> = emptyList(),
)

interface DetectionHistoryRepository {
    suspend fun save(entry: DetectionHistoryEntry)

    suspend fun loadLatest(count: Int = 10): List<DetectionHistoryEntry>

    suspend fun findByFingerprint(fingerprint: String): DetectionHistoryEntry?

    suspend fun clear()
}

@Singleton
class DetectionHistoryStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val dispatchers: AppCoroutineDispatchers,
    ) : DetectionHistoryRepository {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val json = RipDpiJson

        override suspend fun save(entry: DetectionHistoryEntry) =
            withContext(dispatchers.io) {
                val history = loadBlocking()
                val updated =
                    history.copy(
                        entries =
                            (listOf(entry) + history.entries)
                                .distinctBy { it.networkFingerprint }
                                .take(MAX_ENTRIES),
                    )
                prefs.edit { putString(KEY_HISTORY, json.encodeToString(updated)) }
            }

        override suspend fun loadLatest(count: Int): List<DetectionHistoryEntry> =
            withContext(dispatchers.io) {
                loadBlocking().entries.take(count)
            }

        override suspend fun findByFingerprint(fingerprint: String): DetectionHistoryEntry? =
            withContext(dispatchers.io) {
                loadBlocking().entries.firstOrNull { it.networkFingerprint == fingerprint }
            }

        override suspend fun clear() =
            withContext(dispatchers.io) {
                prefs.edit { remove(KEY_HISTORY) }
            }

        private fun loadBlocking(): DetectionHistory =
            try {
                val raw = prefs.getString(KEY_HISTORY, null)
                if (raw != null) json.decodeFromString(raw) else DetectionHistory()
            } catch (_: Exception) {
                DetectionHistory()
            }

        companion object {
            private const val PREFS_NAME = "detection_history"
            private const val KEY_HISTORY = "history_json"
            private const val MAX_ENTRIES = 50
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DetectionHistoryBindingsModule {
    @Binds
    @Singleton
    abstract fun bindDetectionHistoryRepository(store: DetectionHistoryStore): DetectionHistoryRepository
}
