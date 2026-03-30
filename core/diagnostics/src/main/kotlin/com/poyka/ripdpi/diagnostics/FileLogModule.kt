package com.poyka.ripdpi.diagnostics

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FileLogModule {
    @Provides
    @Singleton
    fun provideFileLogWriter(
        @ApplicationContext context: Context,
    ): FileLogWriter = FileLogWriter(context.filesDir)
}
