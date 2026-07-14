package com.poyka.ripdpi.activities

import com.poyka.ripdpi.platform.StringResolver

internal fun testStringResolver(): StringResolver = TestStringResolver

internal fun testDiagnosticsUiCoreSupport(
    formatter: DiagnosticsUiFormatter = DiagnosticsUiFormatter(),
): DiagnosticsUiCoreSupport = DiagnosticsUiCoreSupport(formatter, testStringResolver())

private object TestStringResolver : StringResolver {
    override fun getString(
        resId: Int,
        vararg formatArgs: Any,
    ): String =
        if (formatArgs.isEmpty()) {
            "string-$resId"
        } else {
            formatArgs.joinToString(" · ")
        }
}
