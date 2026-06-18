package com.poyka.ripdpi.data.rules

import android.content.Context
import androidx.room.Room
import com.poyka.ripdpi.data.awg.AwgProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RipDpiDatabaseModule {
    @Provides
    @Singleton
    fun provideRipDpiDatabase(
        @ApplicationContext context: Context,
    ): RipDpiDatabase {
        // Use a local builder so the migration spread does not trip the SpreadOperator
        // detekt rule on a fluent chain. ALL grows by one Migration(N-1, N) per version bump.
        @Suppress("SpreadOperator")
        val builder =
            Room
                .databaseBuilder(
                    context,
                    RipDpiDatabase::class.java,
                    "ripdpi.db",
                ).addMigrations(*RipDpiDatabaseMigrations.ALL)
                .addCallback(RipDpiDatabase.SeedCallback)
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRuleDao(database: RipDpiDatabase): RuleDao = database.ruleDao()

    @Provides
    @Singleton
    fun provideAwgProfileDao(database: RipDpiDatabase): AwgProfileDao = database.awgProfileDao()
}
