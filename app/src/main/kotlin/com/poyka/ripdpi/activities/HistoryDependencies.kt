package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Inject

internal interface HistoryDetailLoader {
    suspend fun loadConnectionDetail(sessionId: String): HistoryConnectionDetailUiModel?

    suspend fun loadDiagnosticsDetail(sessionId: String): DiagnosticsSessionDetailUiModel?
}

internal class DefaultHistoryDetailLoader
@Inject
constructor(
    private val diagnosticsHistorySource: DiagnosticsHistorySource,
    private val diagnosticsDetailLoader: DiagnosticsDetailLoader,
    private val connectionDetailUiFactory: HistoryConnectionDetailUiFactory,
    private val diagnosticsSessionDetailUiMapper: DiagnosticsSessionDetailUiMapper,
) : HistoryDetailLoader {
    override suspend fun loadConnectionDetail(sessionId: String): HistoryConnectionDetailUiModel? =
        diagnosticsHistorySource
            .loadConnectionDetail(sessionId)
            ?.let(connectionDetailUiFactory::toConnectionDetail)

    override suspend fun loadDiagnosticsDetail(sessionId: String): DiagnosticsSessionDetailUiModel? =
        diagnosticsSessionDetailUiMapper.toSessionDetailUiModel(
            detail = diagnosticsDetailLoader.loadSessionDetail(sessionId),
            showSensitiveDetails = false,
        )
}

@Module
@InstallIn(ViewModelComponent::class)
internal abstract class HistoryViewModelModule {
    @Binds
    abstract fun bindHistoryDetailLoader(loader: DefaultHistoryDetailLoader): HistoryDetailLoader

    @Binds
    abstract fun bindDiagnosticsSessionDetailUiMapper(
        factory: DiagnosticsSessionDetailUiFactory,
    ): DiagnosticsSessionDetailUiMapper
}
