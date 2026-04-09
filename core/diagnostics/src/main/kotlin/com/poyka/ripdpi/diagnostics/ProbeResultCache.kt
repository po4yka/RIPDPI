package com.poyka.ripdpi.diagnostics

import android.content.Context
import co.touchlab.kermit.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Serializable
data class CachedProbeOutcome(
    val fingerprintHash: String,
    val headline: String,
    val summary: String,
    val appliedSettings: List<DiagnosticsAppliedSetting>,
    val completedStageCount: Int,
    val failedStageCount: Int,
    val cachedAtMs: Long,
)

interface ProbeResultCache {
    suspend fun lookup(fingerprintHash: String): CachedProbeOutcome?

    suspend fun store(outcome: CachedProbeOutcome)

    suspend fun evict(fingerprintHash: String)

    suspend fun clear()
}

@Singleton
class DefaultProbeResultCache
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @param:Named("diagnosticsJson") private val json: Json,
    ) : ProbeResultCache {
        private companion object {
            private const val MAX_ENTRIES = 10
            private const val TTL_MS = 24L * 60L * 60L * 1_000L
            private val log = Logger.withTag("ProbeResultCache")
        }

        private val cacheFile = File(context.filesDir, "probe_result_cache.json")
        private val mutex = Mutex()
        private var entries: MutableMap<String, CachedProbeOutcome>? = null

        private fun ensureLoaded(): MutableMap<String, CachedProbeOutcome> {
            entries?.let { return it }
            val loaded =
                runCatching {
                    if (cacheFile.exists()) {
                        json
                            .decodeFromString(
                                MapSerializer(String.serializer(), CachedProbeOutcome.serializer()),
                                cacheFile.readText(),
                            ).toMutableMap()
                    } else {
                        mutableMapOf()
                    }
                }.getOrElse { error ->
                    log.w(error) { "Failed to load probe result cache, starting fresh" }
                    mutableMapOf()
                }
            entries = loaded
            return loaded
        }

        private fun persist(map: Map<String, CachedProbeOutcome>) {
            runCatching {
                cacheFile.writeText(
                    json.encodeToString(
                        MapSerializer(String.serializer(), CachedProbeOutcome.serializer()),
                        map,
                    ),
                )
            }.onFailure { error ->
                log.w(error) { "Failed to persist probe result cache" }
            }
        }

        override suspend fun lookup(fingerprintHash: String): CachedProbeOutcome? =
            mutex.withLock {
                val map = ensureLoaded()
                val entry = map[fingerprintHash] ?: return@withLock null
                val now = System.currentTimeMillis()
                if (now - entry.cachedAtMs > TTL_MS) {
                    map.remove(fingerprintHash)
                    persist(map)
                    return@withLock null
                }
                entry
            }

        override suspend fun store(outcome: CachedProbeOutcome) =
            mutex.withLock {
                val map = ensureLoaded()
                map[outcome.fingerprintHash] = outcome
                if (map.size > MAX_ENTRIES) {
                    val oldest = map.entries.minByOrNull { it.value.cachedAtMs }?.key
                    if (oldest != null) {
                        map.remove(oldest)
                    }
                }
                persist(map)
            }

        override suspend fun evict(fingerprintHash: String) =
            mutex.withLock {
                val map = ensureLoaded()
                if (map.remove(fingerprintHash) != null) {
                    persist(map)
                }
            }

        override suspend fun clear() =
            mutex.withLock {
                val map = ensureLoaded()
                map.clear()
                persist(map)
            }
    }
