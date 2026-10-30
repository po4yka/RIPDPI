package com.poyka.ripdpi.integration

import android.content.Intent
import com.kaspersky.components.composesupport.config.addComposeSupport
import com.kaspersky.kaspresso.interceptors.watcher.testcase.impl.screenshot.ScreenshotFailStepWatcherInterceptor
import com.kaspersky.kaspresso.interceptors.watcher.testcase.impl.views.DumpViewsInterceptor
import com.kaspersky.kaspresso.kaspresso.Kaspresso
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import dagger.hilt.android.testing.HiltAndroidRule
import org.junit.Before
import org.junit.Rule

/**
 * Base for Kaspresso UI journeys: Hilt injection, then [MainActivity] started through the
 * debug automation launch contract. Subclasses still need `@HiltAndroidTest`.
 */
abstract class RipDpiKaspressoTest(
    launchIntent: Intent,
) : TestCase(kaspressoBuilder = journeyKaspressoBuilder()) {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAutomationComposeRule(launchIntent)

    @Before
    fun injectHilt() {
        hiltRule.inject()
    }
}

/**
 * Compose support plus failure evidence: a screenshot of the failed step and the view hierarchy, written to
 * `/sdcard/Documents/{screenshots,view_hierarchy}/<class>/<test>` where CI pulls them.
 */
private fun journeyKaspressoBuilder(): Kaspresso.Builder =
    Kaspresso.Builder
        .simple()
        .apply {
            stepWatcherInterceptors.add(ScreenshotFailStepWatcherInterceptor(screenshots))
            testRunWatcherInterceptors.add(DumpViewsInterceptor(viewHierarchyDumper))
        }.addComposeSupport()
