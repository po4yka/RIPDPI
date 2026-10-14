package com.poyka.ripdpi.activities

import com.poyka.ripdpi.platform.StringResolver
import org.robolectric.RuntimeEnvironment

internal fun testStringResolver(): StringResolver = TestStringResolver

internal fun testDiagnosticsUiCoreSupport(
    formatter: DiagnosticsUiFormatter = DiagnosticsUiFormatter(RuntimeEnvironment.getApplication()),
): DiagnosticsUiCoreSupport = DiagnosticsUiCoreSupport(formatter, testStringResolver())

private object TestStringResolver : StringResolver {
    override fun getQuantityString(
        resId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String =
        org.robolectric.RuntimeEnvironment
            .getApplication()
            .resources
            .getQuantityString(resId, quantity, *formatArgs)

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
