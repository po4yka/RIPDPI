package com.poyka.ripdpi.ui.screens.home

import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.AppStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenLogicTest {
    @Test
    fun `starts connection when disconnected and service is halted`() {
        val uiState =
            MainUiState(
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Disconnected,
            )

        assertTrue(shouldStartConnection(uiState))
    }

    @Test
    fun `starts connection when last attempt ended in error and service is halted`() {
        val uiState =
            MainUiState(
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Error,
            )

        assertTrue(shouldStartConnection(uiState))
    }

    @Test
    fun `does not treat connected state as a start request`() {
        val uiState =
            MainUiState(
                appStatus = AppStatus.Running,
                connectionState = ConnectionState.Connected,
            )

        assertFalse(shouldStartConnection(uiState))
    }

    @Test
    fun `does not treat connecting state as a start request`() {
        val uiState =
            MainUiState(
                appStatus = AppStatus.Halted,
                connectionState = ConnectionState.Connecting,
            )

        assertFalse(shouldStartConnection(uiState))
    }

    @Test
    fun `does not treat running service as a fresh start request`() {
        val uiState =
            MainUiState(
                appStatus = AppStatus.Running,
                connectionState = ConnectionState.Error,
            )

        assertFalse(shouldStartConnection(uiState))
    }
}
