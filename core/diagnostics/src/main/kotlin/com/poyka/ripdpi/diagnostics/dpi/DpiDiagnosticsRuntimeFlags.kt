package com.poyka.ripdpi.diagnostics.dpi

object DpiDiagnosticsRuntimeFlags {
    val includeQuic: Boolean
        get() =
            System
                .getProperty(IncludeQuicProperty)
                ?.toBooleanStrictOrNull()
                ?: true

    private const val IncludeQuicProperty = "dpi.diagnostics.includeQuic"
}
