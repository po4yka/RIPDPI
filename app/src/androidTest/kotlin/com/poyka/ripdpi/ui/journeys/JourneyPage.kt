package com.poyka.ripdpi.ui.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode
import kotlinx.coroutines.runBlocking

internal typealias JourneyRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private const val DefaultWaitMillis = 10_000L

/**
 * Page object for one screen of a UI journey. Subclasses name the screen's test tags as properties;
 * the actions below take a tag and work for lazy-list items that are not composed yet.
 * Use it as `with(ConfigPage(composeRule)) { click(vpnChip) }`.
 */
internal abstract class JourneyPage<T : JourneyPage<T>>(
    val rule: JourneyRule,
    val screenTag: String,
) : ComposeScreen<T>(rule, { hasTestTag(screenTag) }) {
    fun node(tag: String): KNode = onNode { hasTestTag(tag) }

    fun awaitScreen(timeoutMillis: Long = DefaultWaitMillis) = rule.waitForTag(screenTag, timeoutMillis)

    fun exists(tag: String): Boolean = rule.tagExists(tag)

    fun count(tag: String): Int = rule.tagCount(tag)

    fun waitFor(
        tag: String,
        timeoutMillis: Long = DefaultWaitMillis,
    ) = rule.waitForTag(tag, timeoutMillis)

    fun waitGone(
        tag: String,
        timeoutMillis: Long = DefaultWaitMillis,
    ) = rule.waitForNoTag(tag, timeoutMillis)

    fun reveal(tag: String): Boolean = rule.revealTag(tag)

    fun require(tag: String) = rule.requireTag(tag)

    fun click(tag: String) = rule.clickTag(tag)

    /**
     * Runs the node's click action without an injected touch. Use it only where a window outside
     * Compose idling (the soft keyboard, a just-inserted lazy item) can swallow a tap at that moment.
     */
    fun activate(tag: String) {
        rule.requireTag(tag)
        rule.onAllNodes(hasTestTag(tag)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
    }

    fun awaitDisplayed(tag: String) = rule.awaitDisplayed(tag)

    fun isSelected(tag: String): Boolean = rule.isSelected(tag)

    fun awaitSelected(tag: String) = rule.waitUntil(DefaultWaitMillis) { rule.isSelected(tag) }

    fun toggleState(tag: String): ToggleableState? = rule.toggleState(tag)

    fun stateDescription(tag: String): String? = rule.stateDescription(tag)

    fun pressBack() = rule.pressBack()
}

internal fun tagStartingWith(prefix: String): SemanticsMatcher =
    SemanticsMatcher("test tag starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

internal fun JourneyRule.exists(matcher: SemanticsMatcher): Boolean =
    onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

internal fun JourneyRule.tagExists(tag: String): Boolean = exists(hasTestTag(tag))

internal fun JourneyRule.tagCount(tag: String): Int = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

/** Waits for [condition]; on timeout the failure carries the semantics tree. */
internal fun JourneyRule.waitWithTree(
    what: String,
    timeoutMillis: Long = DefaultWaitMillis,
    condition: () -> Boolean,
) {
    try {
        waitUntil(timeoutMillis, condition)
    } catch (error: ComposeTimeoutException) {
        throw AssertionError("$what\n${onRoot(useUnmergedTree = true).printToString(maxDepth = 12)}", error)
    }
}

internal fun JourneyRule.waitForTag(
    tag: String,
    timeoutMillis: Long = DefaultWaitMillis,
) = waitWithTree("tag $tag never appeared", timeoutMillis) { tagExists(tag) }

internal fun JourneyRule.waitForNoTag(
    tag: String,
    timeoutMillis: Long = DefaultWaitMillis,
) = waitWithTree("tag $tag still present", timeoutMillis) { !tagExists(tag) }

internal fun JourneyRule.waitForSettings(
    repository: AppSettingsRepository,
    timeoutMillis: Long = DefaultWaitMillis,
    predicate: (AppSettings) -> Boolean,
) = waitWithTree("settings predicate never held", timeoutMillis) {
    predicate(runBlocking { repository.snapshot() })
}

/**
 * Scrolls vertical containers (lazy lists, scroll columns) until [matcher] is composed, then brings
 * it into view. Lazy items off-screen do not exist and Compose drops clicks outside the viewport, so
 * a plain `performScrollTo` is not enough. Returns whether the node exists afterwards.
 */
internal fun JourneyRule.reveal(matcher: SemanticsMatcher): Boolean {
    if (!exists(matcher)) {
        // Vertical containers only: scrolling a horizontal section pager would leave the current page.
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

internal fun JourneyRule.revealTag(tag: String): Boolean = reveal(hasTestTag(tag))

internal fun JourneyRule.requireTag(tag: String) {
    if (!revealTag(tag)) waitForTag(tag)
    check(revealTag(tag)) { "Expected a node with test tag '$tag'" }
}

internal fun JourneyRule.clickTag(tag: String) {
    requireTag(tag)
    onAllNodes(hasTestTag(tag)).onFirst().performClick()
}

/** Reveals the tagged node and waits until it is actually inside the viewport. */
internal fun JourneyRule.awaitDisplayed(tag: String) {
    requireTag(tag)
    waitWithTree("tag $tag never became displayed") {
        val displayed = runCatching { onAllNodes(hasTestTag(tag)).onFirst().assertIsDisplayed() }.isSuccess
        if (!displayed) runCatching { onAllNodes(hasTestTag(tag)).onFirst().performScrollTo() }
        displayed
    }
}

internal fun JourneyRule.toggleState(tag: String): ToggleableState? =
    onAllNodes(hasTestTag(tag))
        .onFirst()
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.ToggleableState)

/** Chips are checkbox toggleables; selectable rows expose `Selected`. Either marks the option as chosen. */
internal fun JourneyRule.isSelected(tag: String): Boolean {
    val nodes = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    val config = nodes.firstOrNull()?.config ?: return false
    return config.getOrNull(SemanticsProperties.Selected) == true ||
        config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
}

internal fun JourneyRule.stateDescription(tag: String): String? =
    onAllNodes(hasTestTag(tag))
        .onFirst()
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.StateDescription)

internal fun JourneyRule.pressBack() {
    // UiDevice delivers Back to the focused window, so it also closes dialog-hosted bottom sheets.
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    waitForIdle()
}
