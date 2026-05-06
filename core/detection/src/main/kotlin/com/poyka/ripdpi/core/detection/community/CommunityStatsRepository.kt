package com.poyka.ripdpi.core.detection.community

import com.poyka.ripdpi.core.detection.DetectionHistoryRepository
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class CommunityStatsRefreshResult(
    val localStats: CommunityStats,
    val remoteStats: CommunityStats?,
    val remoteError: Throwable?,
)

interface CommunityStatsRepository {
    suspend fun loadCachedOrLocal(): CommunityStats?

    suspend fun refresh(): CommunityStatsRefreshResult
}

@Singleton
class DefaultCommunityStatsRepository
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val historyStore: DetectionHistoryRepository,
        private val communityStore: CommunityComparisonStore,
        dispatchers: AppCoroutineDispatchers,
    ) : CommunityStatsRepository {
        private val client = CommunityComparisonClient(dispatchers)

        override suspend fun loadCachedOrLocal(): CommunityStats? {
            val cached = communityStore.getCachedStats()
            if (cached != null) return cached

            val localStats = CommunityComparisonClient.computeLocalStats(historyStore)
            return localStats.takeIf { it.totalReports > 0 }
        }

        override suspend fun refresh(): CommunityStatsRefreshResult {
            val localStats = CommunityComparisonClient.computeLocalStats(historyStore)
            val settings = appSettingsRepository.settings.first()
            val statsUrl =
                settings.communityApiUrl.ifBlank {
                    CommunityComparisonClient.DEFAULT_STATS_URL
                }
            val remoteResult = client.fetchStats(statsUrl)
            remoteResult.onSuccess(communityStore::cacheStats)
            return CommunityStatsRefreshResult(
                localStats = localStats,
                remoteStats = remoteResult.getOrNull(),
                remoteError = remoteResult.exceptionOrNull(),
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class CommunityStatsRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindCommunityStatsRepository(repository: DefaultCommunityStatsRepository): CommunityStatsRepository
}
