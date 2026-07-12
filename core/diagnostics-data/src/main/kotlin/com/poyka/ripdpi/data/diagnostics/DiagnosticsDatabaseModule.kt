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
        // Use a local variable to apply migrations without triggering SpreadOperator detekt rule
        // on a fluent chain. ALL is empty at v5 and grows only when migrations are added.
        @Suppress("SpreadOperator")
        var builder =
            Room
                .databaseBuilder(context, DiagnosticsDatabase::class.java, name)
                .addMigrations(*DiagnosticsDatabaseMigrations.ALL)
                .fallbackToDestructiveMigrationOnDowngrade(true)
        // ORDER MATTERS: fallbackToDestructiveMigrationOnDowngrade resets requireMigration=true,
        // so the destructive-upgrade opt-in must come after it.
        if (allowDestructiveFallback) builder = builder.fallbackToDestructiveMigration(true)
        // RuntimeSessionCoordinator applies the current diagnosticsHistoryRetentionDays; an on-open TTL would bypass it.
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideDiagnosticsDao(database: DiagnosticsDatabase): DiagnosticsDao = database.diagnosticsDao()
}
