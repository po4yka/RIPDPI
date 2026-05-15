package com.poyka.ripdpi.data.rules

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
object RipDpiDatabaseModule {
    @Provides
    @Singleton
    fun provideRipDpiDatabase(
        @ApplicationContext context: Context,
    ): RipDpiDatabase =
        Room
            .databaseBuilder(
                context,
                RipDpiDatabase::class.java,
                "ripdpi.db",
            ).addCallback(RipDpiDatabase.SeedCallback)
            .build()

    @Provides
    @Singleton
    fun provideRuleDao(database: RipDpiDatabase): RuleDao = database.ruleDao()
}
