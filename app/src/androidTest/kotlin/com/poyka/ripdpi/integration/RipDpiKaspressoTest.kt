package com.poyka.ripdpi.integration

import android.content.Intent
import com.kaspersky.components.composesupport.config.withComposeSupport
import com.kaspersky.kaspresso.kaspresso.Kaspresso
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import dagger.hilt.android.testing.HiltAndroidRule
import io.github.kakaocup.compose.node.element.KNode
import org.junit.Before
import org.junit.Rule

/** Compose drops a click injected outside the viewport, and Kaspresso auto-scroll only retries checks. */
internal fun KNode.scrollAndClick() {
    performScrollTo()
    performClick()
}

/**
 * Base for Kaspresso UI journeys: Hilt injection, then [MainActivity] started through the
 * debug automation launch contract. Subclasses still need `@HiltAndroidTest`.
 */
abstract class RipDpiKaspressoTest(
    launchIntent: Intent,
) : TestCase(kaspressoBuilder = Kaspresso.Builder.withComposeSupport()) {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAutomationComposeRule(launchIntent)

    @Before
    fun injectHilt() {
        hiltRule.inject()
    }
}
