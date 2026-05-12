package com.poyka.ripdpi.ui.screens.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectionCheckUiStateTest {
    @Test
    fun tlsKeylogWarningPathIsHiddenWhenDebugModeIsOff() {
        val state =
            DetectionCheckUiState(
                debugModeEnabled = false,
                privacyModeEnabled = false,
                tlsKeylogPath = "/app/files/diagnostics/tls.keys",
            )

        assertNull(state.tlsKeylogWarningPath)
    }

    @Test
    fun tlsKeylogWarningPathIsHiddenWhenPrivacyModeIsOn() {
        val state =
            DetectionCheckUiState(
                debugModeEnabled = true,
                privacyModeEnabled = true,
                tlsKeylogPath = "/app/files/diagnostics/tls.keys",
            )

        assertNull(state.tlsKeylogWarningPath)
    }

    @Test
    fun tlsKeylogWarningPathUsesStoredPathWhenEffective() {
        val state =
            DetectionCheckUiState(
                debugModeEnabled = true,
                privacyModeEnabled = false,
                tlsKeylogPath = "/app/files/diagnostics/tls.keys",
            )

        assertEquals("/app/files/diagnostics/tls.keys", state.tlsKeylogWarningPath)
    }
}
