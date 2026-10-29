package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.runBlocking

private const val UiTimeoutMillis = 10_000L

internal fun ComposeTestRule.tagExists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

internal fun ComposeTestRule.awaitTag(
    tag: String,
    timeoutMillis: Long = UiTimeoutMillis,
) {
    waitUntil(timeoutMillis) { tagExists(tag) }
}

internal fun ComposeTestRule.awaitNoTag(
    tag: String,
    timeoutMillis: Long = UiTimeoutMillis,
) {
    waitUntil(timeoutMillis) { !tagExists(tag) }
}

/**
 * Brings the tagged node into view. Lazy lists do not compose off-screen items, so the node is first
 * located by asking every scroll container to scroll to it; plain scroll columns fall through to
 * `performScrollTo`.
 */
internal fun ComposeTestRule.scrollToTag(tag: String) {
    if (!tagExists(tag)) {
        val containers = onAllNodes(hasScrollToNodeAction())
        val count = containers.fetchSemanticsNodes().size
        for (index in 0 until count) {
            runCatching { containers[index].performScrollToNode(hasTestTag(tag)) }
            if (tagExists(tag)) break
        }
    }
    awaitTag(tag)
    runCatching { onNode(hasTestTag(tag)).performScrollTo() }
}

internal fun ComposeTestRule.clickTag(tag: String) {
    scrollToTag(tag)
    onNode(hasTestTag(tag)).performClick()
}

internal fun ComposeTestRule.awaitSettings(
    timeoutMillis: Long = UiTimeoutMillis,
    repository: AppSettingsRepository,
    predicate: (AppSettings) -> Boolean,
) {
    waitUntil(timeoutMillis) { predicate(runBlocking { repository.snapshot() }) }
}

internal fun ComposeTestRule.awaitSelected(
    tag: String,
    timeoutMillis: Long = UiTimeoutMillis,
) {
    waitUntil(timeoutMillis) { onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
}

/** Scrolls to the tagged node and waits until it is actually inside the viewport. */
internal fun ComposeTestRule.awaitDisplayed(
    tag: String,
    timeoutMillis: Long = UiTimeoutMillis,
) {
    scrollToTag(tag)
    waitUntil(timeoutMillis) {
        val displayed = runCatching { onNode(hasTestTag(tag)).assertIsDisplayed() }.isSuccess
        if (!displayed) runCatching { onNode(hasTestTag(tag)).performScrollTo() }
        displayed
    }
}
