package com.poyka.ripdpi.platform

import com.poyka.ripdpi.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object BuildProvenanceModule {
    @Provides
    @Named("gitCommit")
    fun provideGitCommit(): String = BuildConfig.GIT_COMMIT

    @Provides
    @Named("appVersionName")
    fun provideAppVersionName(): String = BuildConfig.VERSION_NAME

    @Provides
    @Named("nativeLibVersion")
    fun provideNativeLibVersion(): String = BuildConfig.NATIVE_LIB_VERSION
}
