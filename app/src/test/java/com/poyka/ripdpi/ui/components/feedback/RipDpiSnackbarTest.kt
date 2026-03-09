package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiSnackbarTest {

    @Test
    fun customVisualsPreserveToneAndMetadata() {
        val visuals = RipDpiSnackbarVisuals(
            message = "VPN permission still needs to be granted.",
            tone = RipDpiSnackbarTone.Warning,
            actionLabel = "Open",
            duration = SnackbarDuration.Long,
            withDismissAction = true,
        )

        assertEquals(RipDpiSnackbarTone.Warning, visuals.ripDpiToneOrDefault())
        assertEquals("Open", visuals.actionLabel)
        assertEquals(SnackbarDuration.Long, visuals.duration)
        assertEquals(true, visuals.withDismissAction)
    }

    @Test
    fun standardVisualsFallBackToDefaultTone() {
        val visuals = object : SnackbarVisuals {
            override val actionLabel: String? = null
            override val duration: SnackbarDuration = SnackbarDuration.Short
            override val message: String = "Logs exported successfully."
            override val withDismissAction: Boolean = false
        }

        assertEquals(RipDpiSnackbarTone.Default, visuals.ripDpiToneOrDefault())
    }
}
