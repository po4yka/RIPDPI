package com.poyka.ripdpi.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal interface DiagnosticsArchiveBuildInfoProvider {
    fun buildProvenance(): DiagnosticsArchiveBuildProvenance
}

@Singleton
internal class AndroidDiagnosticsArchiveBuildInfoProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:Named("gitCommit") private val gitCommit: String,
        @param:Named("nativeLibVersion") private val nativeLibVersion: String,
    ) : DiagnosticsArchiveBuildInfoProvider {
        override fun buildProvenance(): DiagnosticsArchiveBuildProvenance {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            return DiagnosticsArchiveBuildProvenance(
                applicationId = context.packageName,
                appVersionName = packageInfo.versionName ?: "unavailable",
                appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                buildType =
                    if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        "debug"
                    } else {
                        "release"
                    },
                gitCommit = gitCommit,
                nativeLibraries =
                    listOf(
                        DiagnosticsArchiveNativeLibraryProvenance(
                            name = "libripdpi.so",
                            version = nativeLibVersion,
                        ),
                        DiagnosticsArchiveNativeLibraryProvenance(
                            name = "libripdpi-tunnel.so",
                            version = nativeLibVersion,
                        ),
                    ),
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiagnosticsArchiveBuildInfoProviderModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsArchiveBuildInfoProvider(
        provider: AndroidDiagnosticsArchiveBuildInfoProvider,
    ): DiagnosticsArchiveBuildInfoProvider
}
