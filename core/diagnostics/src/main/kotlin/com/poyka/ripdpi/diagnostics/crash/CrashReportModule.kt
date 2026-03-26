package com.poyka.ripdpi.diagnostics.crash

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CrashReportModule {
    @Provides
    @Singleton
    fun provideCrashReportReader(
        @ApplicationContext context: Context,
    ): CrashReportReader = CrashReportReader(context.filesDir)
}
