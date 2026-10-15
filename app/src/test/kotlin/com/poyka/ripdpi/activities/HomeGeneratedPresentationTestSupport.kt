package com.poyka.ripdpi.activities

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.platform.StringResolver
import org.junit.Assert.assertTrue
import java.util.Locale

internal fun projectHome(
    outcome: DiagnosticsHomeCompositeOutcome,
    language: String,
): HomeDiagnosticsUiState {
    val application = ApplicationProvider.getApplicationContext<android.content.Context>()
    val configuration =
        Configuration(application.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        }
    val context = application.createConfigurationContext(configuration)
    val strings =
        object : StringResolver {
            override fun getString(
                resId: Int,
                vararg formatArgs: Any,
            ): String = context.getString(resId, *formatArgs)

            override fun getQuantityString(
                resId: Int,
                quantity: Int,
                vararg formatArgs: Any,
            ): String = context.resources.getQuantityString(resId, quantity, *formatArgs)
        }
    return buildHomeDiagnosticsUiState(
        settings = AppSettingsSerializer.defaultValue,
        appStatus = AppStatus.Halted,
        connectionState = ConnectionState.Disconnected,
        runtime =
            HomeDiagnosticsRuntimeState(
                latestCompositeOutcome = outcome,
                currentFingerprintHash = outcome.fingerprintHash,
                analysisSheetVisible = true,
            ),
        stringResolver = strings,
    )
}

internal fun homeOutcome() =
    DiagnosticsHomeCompositeOutcome(
        runId = "frozen",
        fingerprintHash = "network",
        actionable = true,
        headline = "Custom report",
        summary = "Custom raw summary",
        completedStageCount = 1,
    )

internal fun assertArabic(value: String?) {
    assertTrue("Expected Arabic presentation, got: $value", value.orEmpty().any { it in '\u0600'..'\u06ff' })
}
