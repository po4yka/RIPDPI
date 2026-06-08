package com.poyka.ripdpi.services

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class PrivacyLeakIndicatorStatus {
    NOT_CHECKED,
    CLEAR,
    LEAK_DETECTED,
    UNKNOWN,
}

data class PrivacyLeakIndicatorSnapshot(
    val status: PrivacyLeakIndicatorStatus = PrivacyLeakIndicatorStatus.NOT_CHECKED,
    val detail: String? = null,
    val updatedAtMillis: Long = 0L,
)

data class PrivacyThreatSnapshot(
    val dnsLeak: PrivacyLeakIndicatorSnapshot = PrivacyLeakIndicatorSnapshot(),
    val ipv6Leak: PrivacyLeakIndicatorSnapshot = PrivacyLeakIndicatorSnapshot(),
)

interface PrivacyThreatStateStore {
    val snapshot: StateFlow<PrivacyThreatSnapshot>

    fun updateDnsLeak(indicator: PrivacyLeakIndicatorSnapshot)

    fun updateIpv6Leak(indicator: PrivacyLeakIndicatorSnapshot)

    fun update(snapshot: PrivacyThreatSnapshot)
}

@Singleton
class InMemoryPrivacyThreatStateStore
    @Inject
    constructor() : PrivacyThreatStateStore {
        private val _snapshot = MutableStateFlow(PrivacyThreatSnapshot())

        override val snapshot: StateFlow<PrivacyThreatSnapshot> = _snapshot.asStateFlow()

        override fun updateDnsLeak(indicator: PrivacyLeakIndicatorSnapshot) {
            _snapshot.value = _snapshot.value.copy(dnsLeak = indicator)
        }

        override fun updateIpv6Leak(indicator: PrivacyLeakIndicatorSnapshot) {
            _snapshot.value = _snapshot.value.copy(ipv6Leak = indicator)
        }

        override fun update(snapshot: PrivacyThreatSnapshot) {
            _snapshot.value = snapshot
        }
    }

internal object PrivacyThreatLeakResultMapper {
    fun dns(
        result: DnsLeakCheckResult,
        nowMillis: Long = System.currentTimeMillis(),
    ): PrivacyLeakIndicatorSnapshot =
        when (result) {
            DnsLeakCheckResult.Clean -> {
                PrivacyLeakIndicatorSnapshot(
                    status = PrivacyLeakIndicatorStatus.CLEAR,
                    updatedAtMillis = nowMillis,
                )
            }

            is DnsLeakCheckResult.Leaked -> {
                PrivacyLeakIndicatorSnapshot(
                    status = PrivacyLeakIndicatorStatus.LEAK_DETECTED,
                    detail = "${result.domain} -> ${result.resolverAddress}",
                    updatedAtMillis = nowMillis,
                )
            }
        }

    fun ipv6(
        result: Ipv6LeakCheckResult,
        nowMillis: Long = System.currentTimeMillis(),
    ): PrivacyLeakIndicatorSnapshot =
        when (result) {
            Ipv6LeakCheckResult.Clean -> {
                PrivacyLeakIndicatorSnapshot(
                    status = PrivacyLeakIndicatorStatus.CLEAR,
                    updatedAtMillis = nowMillis,
                )
            }

            is Ipv6LeakCheckResult.Leaked -> {
                PrivacyLeakIndicatorSnapshot(
                    status = PrivacyLeakIndicatorStatus.LEAK_DETECTED,
                    detail = result.sourceAddress,
                    updatedAtMillis = nowMillis,
                )
            }
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PrivacyThreatStateModule {
    @Binds
    @Singleton
    abstract fun bindPrivacyThreatStateStore(store: InMemoryPrivacyThreatStateStore): PrivacyThreatStateStore
}
