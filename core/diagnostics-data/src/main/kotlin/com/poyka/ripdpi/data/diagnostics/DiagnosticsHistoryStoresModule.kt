package com.poyka.ripdpi.data.diagnostics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsHistoryStoresModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsProfileCatalog(store: RoomDiagnosticsProfileCatalog): DiagnosticsProfileCatalog

    @Binds
    @Singleton
    abstract fun bindDiagnosticsScanRecordStore(store: RoomDiagnosticsScanRecordStore): DiagnosticsScanRecordStore

    @Binds
    @Singleton
    abstract fun bindHomeDiagnosticsRunStore(store: RoomHomeDiagnosticsRunStore): HomeDiagnosticsRunStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsArtifactReadStore(store: RoomDiagnosticsArtifactStore): DiagnosticsArtifactReadStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsArtifactQueryStore(store: RoomDiagnosticsArtifactStore): DiagnosticsArtifactQueryStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsArchiveNativeEventQueryStore(
        store: RoomDiagnosticsArtifactStore,
    ): DiagnosticsArchiveNativeEventQueryStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsArtifactWriteStore(store: RoomDiagnosticsArtifactStore): DiagnosticsArtifactWriteStore

    @Binds
    @Singleton
    abstract fun bindRawPathSettlementStore(store: RoomDiagnosticsArtifactStore): RawPathSettlementStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsFailureArtifactWriteStore(
        store: RoomDiagnosticsFailureArtifactStore,
    ): DiagnosticsFailureArtifactWriteStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsDurableStateStore(store: RoomDiagnosticsArtifactStore): DiagnosticsDurableStateStore

    @Binds
    @Singleton
    abstract fun bindBypassUsageHistoryStore(store: RoomBypassUsageHistoryStore): BypassUsageHistoryStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsTerminalOutboxStore(
        store: RoomDiagnosticsTerminalOutboxStore,
    ): DiagnosticsTerminalOutboxStore

    @Binds
    @Singleton
    abstract fun bindRememberedNetworkPolicyRecordStore(
        store: RoomRememberedNetworkPolicyRecordStore,
    ): RememberedNetworkPolicyRecordStore

    @Binds
    @Singleton
    abstract fun bindNetworkDnsPathPreferenceRecordStore(
        store: RoomNetworkDnsPathPreferenceRecordStore,
    ): NetworkDnsPathPreferenceRecordStore

    @Binds
    @Singleton
    abstract fun bindNetworkEdgePreferenceRecordStore(
        store: RoomNetworkEdgePreferenceRecordStore,
    ): NetworkEdgePreferenceRecordStore

    @Binds
    @Singleton
    abstract fun bindNetworkDnsBlockedPathStore(store: RoomNetworkDnsBlockedPathStore): NetworkDnsBlockedPathStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHistoryRetentionStore(
        store: RoomDiagnosticsHistoryRetentionStore,
    ): DiagnosticsHistoryRetentionStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHistoryResetStore(
        store: RoomDiagnosticsHistoryResetStore,
    ): DiagnosticsHistoryResetStore

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHistoryClock(clock: SystemDiagnosticsHistoryClock): DiagnosticsHistoryClock
}
