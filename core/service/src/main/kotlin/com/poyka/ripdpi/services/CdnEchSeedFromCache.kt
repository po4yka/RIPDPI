package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiCdnEchNativeBindings
import com.poyka.ripdpi.data.CdnEchPersistedCache
import javax.inject.Inject
import javax.inject.Singleton

// One-shot helper used at app startup to seed the in-memory ECH cache
// from the EncryptedSharedPreferences-backed persistence layer
// (Phase 3 of ADR-012). Without this hook, every process restart would
// start with a cold cache and serve the static bundled bytes until the
// next refresh cycle, defeating the purpose of persisting the
// refreshed config in the first place.
@Singleton
class CdnEchSeedFromCache
    @Inject
    constructor(
        private val persistedCache: CdnEchPersistedCache,
    ) {
        private val log = Logger.withTag("cdn-ech")

        suspend fun seedIfPresent() {
            val entry = persistedCache.load() ?: run {
                log.d { "cdn-ech persisted cache is empty; nothing to seed" }
                return
            }
            val seedStatus =
                RipDpiCdnEchNativeBindings.seed(
                    RipDpiCdnEchNativeBindings.Snapshot(
                        configBytes = entry.configBytes,
                        fetchedAtUnixMs = entry.fetchedAtUnixMs,
                    ),
                )
            log.i { "cdn-ech native seed result: $seedStatus" }
        }
    }
