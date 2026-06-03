package com.poyka.ripdpi.platform

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.TrimmableCache
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fans an app-backgrounded memory-pressure signal out to every registered
 * [TrimmableCache]. Wired into [com.poyka.ripdpi.RipDpiApp.onTrimMemory] for the
 * `TRIM_MEMORY_UI_HIDDEN` / `TRIM_MEMORY_BACKGROUND` levels.
 *
 * Android 17 caps a single app's memory rather than letting one bloated process
 * push well-behaved cached apps out of memory; voluntarily shedding regenerable
 * caches while the UI is hidden keeps RIPDPI off the chopping block.
 */
@Singleton
class MemoryTrimCoordinator
    @Inject
    constructor(
        private val caches: Set<@JvmSuppressWildcards TrimmableCache>,
    ) {
        private val log = Logger.withTag("MemoryTrim")

        /** Invoked when the app's UI is no longer visible. */
        fun onAppBackgrounded() {
            if (caches.isEmpty()) return
            caches.forEach { cache ->
                runCatching { cache.trimToBackground() }
                    .onFailure { error ->
                        log.w(error) { "TrimmableCache ${cache::class.simpleName} failed to trim" }
                    }
            }
        }
    }

/** Declares the (possibly empty) multibound set of [TrimmableCache]s. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryTrimModule {
    @Multibinds
    abstract fun trimmableCaches(): Set<TrimmableCache>
}
