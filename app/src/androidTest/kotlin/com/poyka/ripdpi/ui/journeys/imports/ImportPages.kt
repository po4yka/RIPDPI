package com.poyka.ripdpi.ui.journeys.imports

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.journeys.exists
import com.poyka.ripdpi.ui.journeys.reveal
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

private const val ImportWaitMillis = 15_000L

internal fun string(
    @StringRes id: Int,
    vararg args: Any,
): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

/**
 * Waits until a node shows exactly [text], scrolling it into composition on small screens; on timeout the failure
 * carries the semantics tree.
 */
internal fun JourneyRule.awaitText(
    text: String,
    timeoutMillis: Long = ImportWaitMillis,
) = waitWithTree("text '$text' never appeared", timeoutMillis) { reveal(hasText(text)) }

internal fun JourneyRule.textShown(text: String): Boolean = exists(hasText(text))

/** Taps the first clickable node labelled [text], for buttons that carry no test tag. */
internal fun JourneyRule.clickText(text: String) {
    awaitText(text)
    onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
}

/**
 * Sends [intent] to the app the way another app would: the system resolves it through the
 * manifest intent filters (only the package is pinned), so the import trampoline runs.
 */
internal fun sendToApp(intent: Intent) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.startActivity(intent.setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun viewLinkIntent(link: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)

internal fun shareTextIntent(text: String): Intent =
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

internal class SubscriptionAddPage(
    rule: JourneyRule,
) : JourneyPage<SubscriptionAddPage>(rule, RipDpiTestTags.screen(Route.SubscriptionAdd)) {
    val url = RipDpiTestTags.SubscriptionAddUrl
    val continueAction = RipDpiTestTags.SubscriptionAddContinue

    fun enterUrl(value: String) {
        require(url)
        node(url).performTextReplacement(value)
    }
}

internal class SubscriptionImportConfirmPage(
    rule: JourneyRule,
) : JourneyPage<SubscriptionImportConfirmPage>(rule, RipDpiTestTags.screen(Route.SubscriptionImportConfirm())) {
    fun confirmAdd() = rule.clickText(string(R.string.import_confirm_add_action))

    fun awaitImportFailed(timeoutMillis: Long = 60_000) =
        rule.awaitText(string(R.string.strategy_config_import_failed_title), timeoutMillis)
}

internal class SubscriptionStatusPage(
    rule: JourneyRule,
) : JourneyPage<SubscriptionStatusPage>(rule, RipDpiTestTags.screen(Route.SubscriptionStatus))

internal class SettingsEntryPage(
    rule: JourneyRule,
) : JourneyPage<SettingsEntryPage>(rule, RipDpiTestTags.screen(Route.Settings)) {
    val bottomNavSettings = RipDpiTestTags.bottomNav(Route.Settings)
    val subscriptionStatus = RipDpiTestTags.SettingsSubscriptionStatus
}

internal class XrayImportPage(
    rule: JourneyRule,
) : JourneyPage<XrayImportPage>(rule, RipDpiTestTags.screen(Route.XrayImport)) {
    val input = "xray_import_input"
    val error = "xray_import_error"

    fun option(name: String): String = "xray_mode_$name"

    fun enterProfile(value: String) {
        require(input)
        node(input).performTextReplacement(value)
    }

    fun validate() = rule.clickText(string(R.string.xray_import_validate_action))

    fun finish() = rule.clickText(string(R.string.xray_import_finish_action))

    fun awaitAccepted() = rule.awaitText(string(R.string.xray_import_capabilities_title))
}
