package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsHistorySource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Inject

internal interface HistoryDetailLoader {
    suspend fun loadConnectionDetail(sessionId: String): DiagnosticConnectionDetail?

    suspend fun loadDiagnosticsDetail(sessionId: String): DiagnosticSessionDetail?
}

internal class DefaultHistoryDetailLoader
    @Inject
    constructor(
        private val diagnosticsHistorySource: DiagnosticsHistorySource,
        private val diagnosticsDetailLoader: DiagnosticsDetailLoader,
    ) : HistoryDetailLoader {
        override suspend fun loadConnectionDetail(sessionId: String): DiagnosticConnectionDetail? =
            diagnosticsHistorySource.loadConnectionDetail(sessionId)

        override suspend fun loadDiagnosticsDetail(sessionId: String): DiagnosticSessionDetail =
            diagnosticsDetailLoader.loadSessionDetail(sessionId)
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
