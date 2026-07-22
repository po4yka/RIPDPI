package com.poyka.ripdpi.ui.screenshot

import androidx.compose.material3.SnackbarHostState
import com.poyka.ripdpi.activities.ConfigPreset
import com.poyka.ripdpi.activities.ConfigPresetKind
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.ui.screens.config.ModeEditorScreen
import com.poyka.ripdpi.ui.screens.config.NoOpModeEditorActions
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

    private fun modeEditorState(): ConfigUiState {
        val draft = AppSettingsSerializer.defaultValue.toConfigDraft()
        return ConfigUiState(
            activeMode = draft.mode,
            presets = buildConfigPresets(draft),
            editingPreset =
                ConfigPreset(
                    id = "custom",
                    kind = ConfigPresetKind.Custom,
                    draft = draft,
                ),
            draft = draft,
        )
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.ModeEditorScreenshotTest"
    }
}
