package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsDatabaseModule {
    @Provides
    @Singleton
    fun provideDiagnosticsDatabase(
        @ApplicationContext context: Context,
    ): DiagnosticsDatabase = buildDiagnosticsDatabase(context, allowDestructiveFallback = false)

    internal fun buildDiagnosticsDatabase(
        context: Context,
        name: String = "diagnostics.db",
        allowDestructiveFallback: Boolean = false,
    ): DiagnosticsDatabase {
        @Suppress("SpreadOperator")
        var builder =
            Room
                .databaseBuilder(context, DiagnosticsDatabase::class.java, name)
                .addMigrations(*DiagnosticsDatabaseMigrations.ALL)
                .fallbackToDestructiveMigrationFrom(
                    true,
                    *DiagnosticsDatabaseMigrations.DESTRUCTIVE_FROM_VERSIONS,
                ).fallbackToDestructiveMigrationOnDowngrade(true)
        if (allowDestructiveFallback) builder = builder.fallbackToDestructiveMigration(true)
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideDiagnosticsDao(database: DiagnosticsDatabase): DiagnosticsDao = database.diagnosticsDao()
}
