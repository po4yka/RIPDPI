package com.poyka.ripdpi.ui.screenshot

import androidx.compose.material3.SnackbarHostState
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.ui.screens.connection.ConnectionPreset
import com.poyka.ripdpi.ui.screens.connection.ConnectionPresetKind
import com.poyka.ripdpi.ui.screens.connection.ConnectionUiState
import com.poyka.ripdpi.ui.screens.connection.ModeEditorScreen
import com.poyka.ripdpi.ui.screens.connection.NoOpModeEditorActions
import com.poyka.ripdpi.ui.screens.connection.buildConfigPresets
import com.poyka.ripdpi.ui.screens.connection.toConfigDraft
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ModeEditorScreenshotTest {
    @Test
    fun hydrationLoading() {
        captureScreenBothThemes(
            name = "hydrationLoading",
            widthDp = 420,
            heightDp = 900,
            testClassFqn = FQN,
        ) {
            ModeEditorScreen(
                uiState = modeEditorState().copy(isEditorLoading = true),
                snackbarHostState = SnackbarHostState(),
                actions = NoOpModeEditorActions,
            )
        }
    }

    @Test
    fun saving() {
        captureScreenBothThemes(
            name = "saving",
            widthDp = 420,
            heightDp = 900,
            testClassFqn = FQN,
        ) {
            ModeEditorScreen(
                uiState = modeEditorState().copy(isEditorSaving = true),
                snackbarHostState = SnackbarHostState(),
                actions = NoOpModeEditorActions,
            )
        }
    }

    private fun modeEditorState(): ConnectionUiState {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft()
        return ConnectionUiState(
            activeMode = draft.mode,
            presets = buildConfigPresets(draft),
            editingPreset =
                ConnectionPreset(
                    id = "custom",
                    kind = ConnectionPresetKind.Custom,
                    draft = draft,
                ),
            draft = draft,
        )
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.ModeEditorScreenshotTest"
    }
}
