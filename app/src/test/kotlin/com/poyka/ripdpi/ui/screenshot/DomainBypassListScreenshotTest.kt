package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.data.rules.DomainBypassList
import com.poyka.ripdpi.ui.screens.settings.DomainBypassListScreen
import com.poyka.ripdpi.ui.screens.settings.DomainBypassListScreenState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DomainBypassListScreenshotTest {
    @Test
    fun emptyState() {
        capture(
            "emptyState",
            DomainBypassListScreenState(
                text = "",
                errors = emptyList(),
                cleanCount = 0,
                hasRule = false,
            ),
        )
    }

    @Test
    fun populatedState() {
        capture(
            "populatedState",
            DomainBypassListScreenState(
                text = "bank.ru\n.gov.ru\ndomain_suffix:example.com",
                errors = emptyList(),
                cleanCount = 3,
                hasRule = true,
            ),
        )
    }

    @Test
    fun validationErrorState() {
        capture(
            "validationErrorState",
            DomainBypassListScreenState(
                text = "bank.ru\n.gov.ru\ndomain_regex:^(bad\nnot a domain",
                errors =
                    listOf(
                        DomainBypassList.BypassListError(
                            lineNumber = 3,
                            raw = "domain_regex:^(bad",
                            message = "Invalid regex: unclosed group",
                        ),
                        DomainBypassList.BypassListError(
                            lineNumber = 4,
                            raw = "not a domain",
                            message = "Domain must not contain spaces",
                        ),
                    ),
                cleanCount = 2,
                hasRule = true,
            ),
        )
    }

    private fun capture(
        name: String,
        state: DomainBypassListScreenState,
    ) {
        captureScreenBothThemes(
            name = name,
            widthDp = 420,
            heightDp = 1200,
            testClassFqn = FQN,
        ) {
            DomainBypassListScreen(
                state = state,
                onBack = {},
                onTextChanged = {},
                onSave = {},
                onPasteFromClipboard = {},
                onCopyToClipboard = {},
                onMoveToRuleEditor = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.DomainBypassListScreenshotTest"
    }
}
