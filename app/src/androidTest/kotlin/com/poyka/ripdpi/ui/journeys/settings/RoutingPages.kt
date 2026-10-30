package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.journeys.pressBack
import com.poyka.ripdpi.ui.journeys.tagExists
import com.poyka.ripdpi.ui.journeys.waitForTag
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

/** Settings rows that open the routing and maintenance screens. */
internal class SettingsRoutingEntryPage(
    rule: JourneyRule,
) : JourneyPage<SettingsRoutingEntryPage>(rule, RipDpiTestTags.screen(Route.Settings)) {
    val domainBypass = RipDpiTestTags.SettingsDomainBypass
    val routingRules = RipDpiTestTags.SettingsRoutingRules
    val splitTunnel = RipDpiTestTags.SettingsSplitTunnel
    val backupRestore = RipDpiTestTags.SettingsBackupRestore
    private val advancedConnectivity = RipDpiTestTags.SettingsAdvancedConnectivity

    /** Opens a row of the advanced connectivity group, expanding the group when it is collapsed. */
    fun openAdvancedRow(tag: String) {
        awaitScreen()
        if (!reveal(tag)) click(advancedConnectivity)
        click(tag)
    }
}

internal class SplitTunnelPage(
    rule: JourneyRule,
) : JourneyPage<SplitTunnelPage>(rule, RipDpiTestTags.screen(Route.SplitTunnel)) {
    val modeSelector = RipDpiTestTags.SplitTunnelModeSelector
    val editApps = RipDpiTestTags.SplitTunnelEditApps
    val openSystemSettings = RipDpiTestTags.SplitTunnelOpenSystemSettings
    val appPicker = RipDpiTestTags.AppPickerSheet
    val appSearch = RipDpiTestTags.AppPickerSearch
    val appPickerDone = RipDpiTestTags.AppPickerDone

    fun app(packageName: String): String = RipDpiTestTags.appPickerApp(packageName)

    private val segment = hasParent(hasTestTag(modeSelector)) and isSelectable()

    /** Segments in screen order: 0 off, 1 exclude, 2 include. */
    fun selectMode(index: Int) {
        require(modeSelector)
        rule.onAllNodes(segment)[index].performClick()
    }

    fun awaitModeSelected(index: Int) =
        rule.waitWithTree("split tunnel segment $index never became selected") {
            val nodes = rule.onAllNodes(segment).fetchSemanticsNodes()
            nodes.getOrNull(index)?.config?.getOrNull(SemanticsProperties.Selected) == true
        }

    fun searchApp(query: String) {
        require(appSearch)
        node(appSearch).performTextReplacement(query)
    }
}

internal class DomainBypassPage(
    rule: JourneyRule,
) : JourneyPage<DomainBypassPage>(rule, RipDpiTestTags.screen(Route.DomainBypassList)) {
    val editor = RipDpiTestTags.DomainBypassEditor
    val save = RipDpiTestTags.DomainBypassSave

    fun replaceText(value: String) {
        require(editor)
        node(editor).performTextReplacement(value)
    }

    fun awaitEditorText(expected: String) = rule.awaitFieldText(editor, expected)

    /** Save stays disabled until the stored list has loaded into the draft. */
    fun awaitSaveEnabled() =
        rule.waitWithTree("domain bypass save never became enabled") {
            rule.onAllNodes(hasTestTag(save) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
}

internal class RoutesPage(
    rule: JourneyRule,
) : JourneyPage<RoutesPage>(rule, RipDpiTestTags.screen(Route.Routes)) {
    val addRule = RipDpiTestTags.RoutesAddRule
    val deleteConfirm = RipDpiTestTags.RoutesDeleteConfirm

    fun delete(ruleId: Long): String = RipDpiTestTags.routesRuleDelete(ruleId)
}

internal class RuleEditorPage(
    rule: JourneyRule,
) : JourneyPage<RuleEditorPage>(rule, RipDpiTestTags.screen(Route.RuleEditor())) {
    val name = RipDpiTestTags.RuleEditorName
    val domains = RipDpiTestTags.RuleEditorDomains
    val save = RipDpiTestTags.RuleEditorSave

    fun replaceText(
        tag: String,
        value: String,
    ) {
        require(tag)
        node(tag).performTextReplacement(value)
    }
}

internal class RememberedNetworksEntryPage(
    rule: JourneyRule,
) : JourneyPage<RememberedNetworksEntryPage>(rule, RipDpiTestTags.screen(Route.AdvancedSettings)) {
    val inspect = RipDpiTestTags.AdvancedInspectRememberedNetworks

    /** The inspect row is disabled while no network is remembered. */
    fun awaitInspectEnabled() {
        require(inspect)
        rule.waitWithTree("remembered networks row never became enabled") {
            rule.onAllNodes(hasTestTag(inspect) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

internal class RememberedNetworksPage(
    rule: JourneyRule,
) : JourneyPage<RememberedNetworksPage>(rule, RipDpiTestTags.screen(Route.RememberedNetworks)) {
    val deleteConfirm = RipDpiTestTags.RememberedNetworksDeleteConfirm

    fun card(id: Long): String = RipDpiTestTags.rememberedNetworkCard(id)

    fun delete(id: Long): String = RipDpiTestTags.rememberedNetworkDelete(id)
}

internal class BackupRestorePage(
    rule: JourneyRule,
) : JourneyPage<BackupRestorePage>(rule, RipDpiTestTags.screen(Route.BackupRestore)) {
    val exportButton = RipDpiTestTags.BackupExportButton
    val variantShare = RipDpiTestTags.BackupVariantShare
    val snackbar = RipDpiTestTags.BackupExportSnackbar
    val importButton = RipDpiTestTags.BackupImportButton
    val importPreview = RipDpiTestTags.BackupImportPreviewSheet
    val importRoutes = RipDpiTestTags.BackupImportToggleRoutes
    val importConfirm = RipDpiTestTags.BackupImportConfirm

    /**
     * Waits until the node with [tag] or one of its descendants shows [text]. The import preview is
     * a separate window, so this uses a plain wait: the tree dump of `waitWithTree` needs one root.
     */
    fun awaitText(
        tag: String,
        text: String,
    ) {
        val matcher = (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and hasText(text, substring = true)
        try {
            rule.waitUntil(10_000) {
                rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: ComposeTimeoutException) {
            val shown =
                rule
                    .onAllNodes(hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            throw AssertionError("tag $tag never showed '$text'; it shows $shown", error)
        }
    }
}

/** Waits until the text field with [tag] holds exactly [expected]. */
internal fun JourneyRule.awaitFieldText(
    tag: String,
    expected: String,
) = waitWithTree("field $tag never held '$expected'") {
    onAllNodes(hasTestTag(tag))
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.config
        ?.getOrNull(SemanticsProperties.EditableText)
        ?.text == expected
}

/**
 * Presses Back until the screen with [screenTag] shows. The first Back only hides the soft keyboard
 * when a text field still has focus.
 */
internal fun JourneyRule.backTo(screenTag: String) {
    repeat(3) {
        if (tagExists(screenTag)) return
        pressBack()
    }
    waitForTag(screenTag)
}
