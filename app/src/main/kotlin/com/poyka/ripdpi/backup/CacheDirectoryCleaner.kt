package com.poyka.ripdpi.backup

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recursively empties the app's cache directories during a reset.
 *
 * Caches are scratch space (decoded assets, redacted-share temp files, export
 * staging, Coil/HTTP caches), so they are cleared on reset to leave a clean slate.
 * The directories themselves are kept — only their contents are removed — so the
 * framework's `cacheDir` / `codeCacheDir` handles stay valid for the next session.
 * App install state, keystore entries, and permission grants live elsewhere and are
 * untouched.
 */
interface CacheDirectoryCleaner {
    /** Recursively deletes the contents of every cache directory. */
    fun clearCaches()
}

@Singleton
class DefaultCacheDirectoryCleaner
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CacheDirectoryCleaner {
        override fun clearCaches() {
            clearDirectoryContents(context.cacheDir)
            clearDirectoryContents(context.codeCacheDir)
            // External cache dirs are null when no external storage is mounted.
            context.externalCacheDir?.let(::clearDirectoryContents)
        }

        private fun clearDirectoryContents(dir: File?) {
            if (dir == null) return
            runCatching {
                dir.listFiles()?.forEach { it.deleteRecursively() }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class CacheDirectoryCleanerModule {
    @Binds
    @Singleton
    abstract fun bindCacheDirectoryCleaner(cleaner: DefaultCacheDirectoryCleaner): CacheDirectoryCleaner
}
