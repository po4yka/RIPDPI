package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.DiagnosticsActiveConnectionPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class DiagnosticsInteractionDependencies
    @Inject
    constructor(
        val diagnosticsTimelineSource: DiagnosticsTimelineSource,
        val diagnosticsScanController: DiagnosticsScanController,
        val diagnosticsDetailLoader: DiagnosticsDetailLoader,
        val diagnosticsShareService: DiagnosticsShareService,
        val diagnosticsResolverActions: DiagnosticsResolverActions,
    )

class DiagnosticsContextDependencies
    @Inject
    constructor(
        val appSettingsRepository: AppSettingsRepository,
        @param:ApplicationContext val appContext: Context,
        val rememberedPolicySource: DiagnosticsRememberedPolicySource,
        val activeConnectionPolicySource: DiagnosticsActiveConnectionPolicySource,
        val serviceStateStore: ServiceStateStore,
    )
