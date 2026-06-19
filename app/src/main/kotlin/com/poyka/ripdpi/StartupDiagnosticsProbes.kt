package com.poyka.ripdpi

import com.poyka.ripdpi.diagnostics.exit.LastExitInspector
import com.poyka.ripdpi.diagnostics.profiling.MemoryProfilingRegistrar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Groups the two startup-diagnostics singletons so they can be injected as a
 * single constructor parameter in [AppStartupInitializer], keeping the
 * constructor below the detekt LongParameterList threshold.
 */
@Singleton
class StartupDiagnosticsProbes
    @Inject
    constructor(
        val lastExitInspector: LastExitInspector,
        val memoryProfilingRegistrar: MemoryProfilingRegistrar,
    )
