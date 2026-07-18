package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.screens.settings.StrategyConfigScreen
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigScreenState
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class StrategyConfigScreenScreenshotTest {
    @Test
    fun savingState() {
        captureScreenBothThemes(
            name = "savingState",
            widthDp = 420,
            heightDp = 900,
            testClassFqn = javaClass.name,
        ) {
            StrategyConfigScreen(
                state =
                    StrategyConfigScreenState(
                        source = StrategyConfigSource.CustomYaml,
                        configText = "version: 1\nstrategies:\n  - tcp: split\n",
                        luaPath = "",
                        luaFunction = "",
                        activePath = "imported.yaml",
                        banner = null,
                        isSaving = true,
                    ),
                onBack = {},
                onSourceChanged = {},
                onConfigTextChanged = {},
                onLuaPathChanged = {},
                onLuaFunctionChanged = {},
                onImport = {},
                onExport = {},
                onSave = {},
                onReload = {},
                onValidateLua = {},
            )
        }
    }
}
