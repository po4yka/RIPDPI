package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.journeys.exists
import com.poyka.ripdpi.ui.journeys.tagStartingWith
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.settings.ActivationWindowDimension
import com.poyka.ripdpi.ui.screens.settings.AdvancedOptionSetting
import com.poyka.ripdpi.ui.screens.settings.AdvancedTextSetting
import com.poyka.ripdpi.ui.screens.settings.AdvancedToggleSetting
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

private const val ProfileRowPrefix = "config-vpn-profile-row-"

internal class ConfigPage(
    rule: JourneyRule,
) : JourneyPage<ConfigPage>(rule, RipDpiTestTags.screen(Route.Config)) {
    val vpnChip = RipDpiTestTags.configMode(Mode.VPN.name)
    val proxyChip = RipDpiTestTags.configMode(Mode.Proxy.name)
    val localBypassSummary = RipDpiTestTags.ConfigLocalBypassSummary
    val localBypassListenAddress = RipDpiTestTags.ConfigLocalBypassListenAddress
    val localBypassDesync = RipDpiTestTags.ConfigLocalBypassDesync
    val localBypassStrategyAuto = RipDpiTestTags.ConfigLocalBypassStrategyAuto
    val localBypassPrecedenceNote = RipDpiTestTags.ConfigLocalBypassPrecedenceNote
    val localBypassRetest = RipDpiTestTags.ConfigLocalBypassRetest
    val vpnSummary = RipDpiTestTags.ConfigVpnSummary
    val vpnRelay = RipDpiTestTags.ConfigVpnRelay
    val vpnAddServerPaste = RipDpiTestTags.ConfigVpnAddServerPaste
    val vpnAddServerScan = RipDpiTestTags.ConfigVpnAddServerScan
    val dnsSettings = RipDpiTestTags.ConfigDnsSettings
    val profileChange = RipDpiTestTags.ConfigProfileChange
    val profileChoices = RipDpiTestTags.ConfigProfileChoices
    val connectionDetails = RipDpiTestTags.ConfigConnectionDetails
    val runtimeAction = RipDpiTestTags.ConfigRuntimeAction
    val bottomNavConfig = RipDpiTestTags.bottomNav(Route.Config)

    fun modeSection(id: String): String = RipDpiTestTags.configModeSection(id)

    fun preset(id: String): String = RipDpiTestTags.configPreset(id)

    fun awaitRuntimeAction(
        label: String,
        timeoutMillis: Long = 10_000,
    ) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodes(hasTestTag(runtimeAction) and hasText(label)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    fun awaitProfileRow(timeoutMillis: Long = 15_000) {
        rule.waitUntil(timeoutMillis) { rule.exists(tagStartingWith(ProfileRowPrefix)) }
    }
}

internal class ModeEditorPage(
    rule: JourneyRule,
) : JourneyPage<ModeEditorPage>(rule, RipDpiTestTags.screen(Route.ModeEditor)) {
    val proxyIp = RipDpiTestTags.ModeEditorProxyIp
    val proxyPort = RipDpiTestTags.ModeEditorProxyPort
    val save = RipDpiTestTags.ModeEditorSave
    val advanced = RipDpiTestTags.ModeEditorAdvanced
    val maxConnections = RipDpiTestTags.ModeEditorMaxConnections
    val bufferSize = RipDpiTestTags.ModeEditorBufferSize
    val defaultTtl = RipDpiTestTags.ModeEditorDefaultTtl
    val commandLineToggle = RipDpiTestTags.ModeEditorCommandLineToggle
    val commandLineArgs = RipDpiTestTags.ModeEditorCommandLineArgs
    val chainRawToggle = RipDpiTestTags.ModeEditorChainRawToggle
    val chainDsl = RipDpiTestTags.ModeEditorChainDsl
    val validationSnackbar = RipDpiTestTags.ModeEditorValidationSnackbar

    fun replaceText(
        tag: String,
        value: String,
    ) {
        require(tag)
        node(tag).performTextReplacement(value)
    }

    fun expandAdvanced() {
        click(advanced)
        waitFor(maxConnections)
    }
}

internal class DnsSettingsPage(
    rule: JourneyRule,
) : JourneyPage<DnsSettingsPage>(rule, RipDpiTestTags.screen(Route.DnsSettings)) {
    val plainMode = RipDpiTestTags.dnsMode(DnsModePlainUdp)
    val encryptedMode = RipDpiTestTags.dnsMode(DnsModeEncrypted)
    val plainAddress = RipDpiTestTags.DnsPlainAddress
    val plainSave = RipDpiTestTags.DnsPlainSave

    fun protocolTag(id: String): String = RipDpiTestTags.dnsProtocol(id)
}

internal class AdvancedSettingsPage(
    rule: JourneyRule,
) : JourneyPage<AdvancedSettingsPage>(rule, RipDpiTestTags.screen(Route.AdvancedSettings)) {
    val retentionInput = RipDpiTestTags.advancedInput(AdvancedTextSetting.DiagnosticsHistoryRetentionDays)
    val retentionSave = RipDpiTestTags.advancedSave(AdvancedTextSetting.DiagnosticsHistoryRetentionDays)
    val proxyPortInput = RipDpiTestTags.advancedInput(AdvancedTextSetting.ProxyPort)
    val proxyPortSave = RipDpiTestTags.advancedSave(AdvancedTextSetting.ProxyPort)
    val tlsFingerprintDropdown = RipDpiTestTags.advancedOption(AdvancedOptionSetting.TlsFingerprintProfile)

    fun section(id: String): String = RipDpiTestTags.advancedSection(id)

    fun toggle(setting: AdvancedToggleSetting): String = RipDpiTestTags.advancedToggle(setting)

    fun dropdownOption(
        dropdown: String,
        value: String,
    ): String = RipDpiTestTags.dropdownOption(dropdown, value)

    fun activationStart(dimension: ActivationWindowDimension): String = RipDpiTestTags.activationStart(dimension)

    fun activationEnd(dimension: ActivationWindowDimension): String = RipDpiTestTags.activationEnd(dimension)

    fun activationSave(dimension: ActivationWindowDimension): String = RipDpiTestTags.activationSave(dimension)

    fun hostPackPreset(id: String): String = RipDpiTestTags.hostPackPreset(id)

    fun replaceText(
        tag: String,
        value: String,
    ) {
        require(tag)
        node(tag).performTextReplacement(value)
    }
}

internal class SettingsPage(
    rule: JourneyRule,
) : JourneyPage<SettingsPage>(rule, RipDpiTestTags.screen(Route.Settings)) {
    val themeDropdown = RipDpiTestTags.SettingsThemeDropdown
    val customization = RipDpiTestTags.SettingsCustomization
    val dataTransparency = RipDpiTestTags.SettingsDataTransparency
    val about = RipDpiTestTags.SettingsAbout
    val logs = RipDpiTestTags.SettingsLogs
    val backupPinWarning = RipDpiTestTags.SettingsBackupPinWarning
    val backupPinClear = RipDpiTestTags.SettingsBackupPinClear
    val backupPinClearConfirm = RipDpiTestTags.SettingsBackupPinClearConfirm
    val bottomNavSettings = RipDpiTestTags.bottomNav(Route.Settings)

    fun themeOption(value: String): String = RipDpiTestTags.dropdownOption(themeDropdown, value)
}

internal class LogsEntryPage(
    rule: JourneyRule,
) : JourneyPage<LogsEntryPage>(rule, RipDpiTestTags.LogsScreen)

internal class AppCustomizationPage(
    rule: JourneyRule,
) : JourneyPage<AppCustomizationPage>(rule, RipDpiTestTags.screen(Route.AppCustomization)) {
    val themedIcon = RipDpiTestTags.CustomizationThemedIcon
    val shapeInfo = RipDpiTestTags.CustomizationShapeInfo
    val shapeInfoSheet = RipDpiTestTags.CustomizationShapeInfoSheet
    val shapeInfoSheetConfirm = RipDpiTestTags.CustomizationShapeInfoSheetConfirm
}

internal class DataTransparencyPage(
    rule: JourneyRule,
) : JourneyPage<DataTransparencyPage>(rule, RipDpiTestTags.screen(Route.DataTransparency))

internal class AboutPage(
    rule: JourneyRule,
) : JourneyPage<AboutPage>(rule, RipDpiTestTags.screen(Route.About)) {
    val sourceCode = RipDpiTestTags.AboutSourceCode
    val readme = RipDpiTestTags.AboutReadme
}

internal class ProfileImportConfirmPage(
    rule: JourneyRule,
) : JourneyPage<ProfileImportConfirmPage>(rule, RipDpiTestTags.screen(Route.ProfileImportConfirm())) {
    fun awaitText(
        text: String,
        timeoutMillis: Long = 10_000,
    ) {
        rule.waitUntil(timeoutMillis) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    fun confirmAdd() {
        rule.onNode(hasText("Add")).performClick()
    }
}
