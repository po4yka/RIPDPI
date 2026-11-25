package com.poyka.ripdpi.diagnostics.export

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplayArchiveRedactor
    @Inject
    constructor() {
        fun redact(detail: String): String =
            redactDiagnosticsArchiveText(detail)
                .replace("<ip-redacted>", Placeholder)

        companion object {
            const val Placeholder: String = "<IP_REDACTED>"
        }
    }
