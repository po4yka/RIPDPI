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

    @Test
    fun recoveryFailureState() {
        captureScreenBothThemes(
            name = "recoveryFailureState",
            widthDp = 420,
            heightDp = 900,
            testClassFqn = javaClass.name,
        ) {
            StrategyConfigScreen(
                state =
                    StrategyConfigScreenState(
                        source = StrategyConfigSource.BuiltIn,
                        configText = "",
                        luaPath = "",
                        luaFunction = "",
                        activePath = "Built-in strategy",
                        banner = null,
                        hasHydrationError = true,
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

    @Test
    @Config(sdk = [35], qualifiers = "de")
    fun recoveryFailureStateGermanLargeFont() {
        captureScreenBothThemes(
            name = "recoveryFailureStateGermanLargeFont",
            widthDp = 411,
            heightDp = 1000,
            fontScale = 1.5f,
            testClassFqn = javaClass.name,
        ) {
            StrategyConfigScreen(
                state =
                    StrategyConfigScreenState(
                        source = StrategyConfigSource.BuiltIn,
                        configText = "",
                        luaPath = "",
                        luaFunction = "",
                        activePath = "Integrierte Strategie",
                        banner = null,
                        hasHydrationError = true,
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

    @Test
    fun restoringState() {
        captureScreenBothThemes(
            name = "restoringState",
            widthDp = 420,
            heightDp = 900,
            testClassFqn = javaClass.name,
        ) {
            StrategyConfigScreen(
                state =
                    StrategyConfigScreenState(
                        source = StrategyConfigSource.BuiltIn,
                        configText = "",
                        luaPath = "",
                        luaFunction = "",
                        activePath = "Built-in strategy",
                        banner = null,
                        isHydrating = true,
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

    @Test
    @Config(sdk = [35], qualifiers = "ru")
    fun customYamlMaximumFontRussian() {
        captureScreenBothThemes(
            name = "customYamlMaximumFontRussian",
            widthDp = 411,
            heightDp = 1_600,
            fontScale = 2f,
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

    @Test
    @Config(sdk = [35], qualifiers = "fa")
    fun luaMaximumFontPersianRtl() {
        captureScreenBothThemes(
            name = "luaMaximumFontPersianRtl",
            widthDp = 411,
            heightDp = 1_600,
            fontScale = 2f,
            testClassFqn = javaClass.name,
        ) {
            StrategyConfigScreen(
                state =
                    StrategyConfigScreenState(
                        source = StrategyConfigSource.LuaScript,
                        configText = "",
                        luaPath = "/storage/emulated/0/strategy.lua",
                        luaFunction = "build_strategy",
                        activePath = "strategy.lua",
                        banner = null,
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
