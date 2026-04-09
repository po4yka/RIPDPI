package com.poyka.ripdpi.core.detection

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

class DetectionHistoryStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(entry: DetectionHistoryEntry) {
        val history = load()
        val updated =
            history.copy(
                entries =
                    (listOf(entry) + history.entries)
                        .distinctBy { it.networkFingerprint }
                        .take(MAX_ENTRIES),
            )
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(updated)).apply()
    }

    fun load(): DetectionHistory =
        try {
            val raw = prefs.getString(KEY_HISTORY, null)
            if (raw != null) json.decodeFromString(raw) else DetectionHistory()
        } catch (_: Exception) {
            DetectionHistory()
        }

    fun findByFingerprint(fingerprint: String): DetectionHistoryEntry? =
        load().entries.firstOrNull { it.networkFingerprint == fingerprint }

    fun latestEntries(count: Int = 10): List<DetectionHistoryEntry> = load().entries.take(count)

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val PREFS_NAME = "detection_history"
        private const val KEY_HISTORY = "history_json"
        private const val MAX_ENTRIES = 50
    }
}
