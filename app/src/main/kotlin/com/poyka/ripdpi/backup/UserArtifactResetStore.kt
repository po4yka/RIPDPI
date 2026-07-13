package com.poyka.ripdpi.backup

import android.content.Context
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

fun interface UserArtifactResetStore {
    fun clearAll(settings: AppSettings)
}

@Singleton
class DefaultUserArtifactResetStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : UserArtifactResetStore {
        override fun clearAll(settings: AppSettings) {
            PreferenceStores.forEach { name ->
                check(
                    context
                        .getSharedPreferences(name, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit(),
                )
            }
            RelativeArtifacts.forEach { relativePath ->
                File(context.filesDir, relativePath).deleteRecursively()
            }
            deleteConfiguredKeylog(settings.detectionDiagnosticTlsKeylogPath)
        }

        private fun deleteConfiguredKeylog(path: String) {
            if (path.isBlank()) return
            val filesRoot = context.filesDir.canonicalFile
            val candidate = File(path).canonicalFile
            if (candidate == filesRoot || !candidate.toPath().startsWith(filesRoot.toPath())) return
            candidate.deleteRecursively()
        }

        private companion object {
            val PreferenceStores = listOf("pin_lockout", "app_lock", "detection_check_prefs", "backup_share_prefs")
            val RelativeArtifacts = listOf("probe_result_cache.json", "crash-reports", "logs", "native_panics")
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class UserArtifactResetStoreModule {
    @Binds
    @Singleton
    abstract fun bindUserArtifactResetStore(store: DefaultUserArtifactResetStore): UserArtifactResetStore
}
