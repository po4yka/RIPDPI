package com.poyka.ripdpi.platform

import android.content.Context
import android.net.TrafficStats
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface TrafficStatsReader {
    fun currentTransferredBytes(): Long
}

@Singleton
class AndroidTrafficStatsReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : TrafficStatsReader {
        override fun currentTransferredBytes(): Long {
            val uid = context.applicationInfo.uid
            val txBytes = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0L
            val rxBytes = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0L
            return txBytes + rxBytes
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class TrafficStatsBindingsModule {
    @Binds
    @Singleton
    abstract fun bindTrafficStatsReader(
        reader: AndroidTrafficStatsReader,
    ): TrafficStatsReader
}
