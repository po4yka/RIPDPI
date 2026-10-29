package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.activities.MainActivity

internal typealias AutomationRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private const val DefaultWaitMillis = 10_000L

internal fun tagStartingWith(prefix: String): SemanticsMatcher =
    SemanticsMatcher("test tag starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

internal fun AutomationRule.exists(matcher: SemanticsMatcher): Boolean =
    onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

internal fun AutomationRule.tagExists(tag: String): Boolean = exists(hasTestTag(tag))

internal fun AutomationRule.waitForTag(
    tag: String,
    timeoutMillis: Long = DefaultWaitMillis,
) {
    waitUntil(timeoutMillis) { tagExists(tag) }
}

internal fun AutomationRule.waitForNoTag(
    tag: String,
    timeoutMillis: Long = DefaultWaitMillis,
) {
    waitUntil(timeoutMillis) { !tagExists(tag) }
}

/**
 * Scrolls every scrollable container (pager pages, lazy lists) until [matcher] is composed, then
 * brings it into view. Compose drops clicks on off-screen nodes and lazy items off-screen do not
 * exist, so a plain `performScrollTo` is not enough. Returns whether the node exists afterwards.
 */
internal fun AutomationRule.reveal(matcher: SemanticsMatcher): Boolean {
    if (!exists(matcher)) {
        // Vertical containers only: scrolling the horizontal section pager would leave the current page.
        val verticalLists =
            hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val containerCount = onAllNodes(verticalLists).fetchSemanticsNodes().size
        for (index in 0 until containerCount) {
            runCatching { onAllNodes(verticalLists)[index].performScrollToNode(matcher) }
            if (exists(matcher)) break
        }
    }
    val found = exists(matcher)
    if (found) {
        runCatching { onAllNodes(matcher).onFirst().performScrollTo() }
    }
    return found
}

internal fun AutomationRule.revealTag(tag: String): Boolean = reveal(hasTestTag(tag))

internal fun AutomationRule.requireTag(tag: String) {
    check(revealTag(tag)) { "Expected a node with test tag '$tag'" }
}

internal fun AutomationRule.clickTag(tag: String) {
    requireTag(tag)
    onAllNodes(hasTestTag(tag)).onFirst().performClick()
}

internal fun AutomationRule.toggleState(tag: String): ToggleableState? =
    onAllNodes(hasTestTag(tag))
        .onFirst()
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.ToggleableState)

/** Chips are checkbox toggleables; selectable rows expose `Selected`. Either marks the option as chosen. */
internal fun AutomationRule.isSelected(tag: String): Boolean {
    val config = onAllNodes(hasTestTag(tag)).onFirst().fetchSemanticsNode().config
    return config.getOrNull(SemanticsProperties.Selected) == true ||
        config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
}

internal fun AutomationRule.stateDescription(tag: String): String? =
    onAllNodes(hasTestTag(tag))
        .onFirst()
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.StateDescription)

internal fun AutomationRule.pressBack() {
    // UiDevice delivers Back to the focused window, so it also closes dialog-hosted bottom sheets.
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    waitForIdle()
}
