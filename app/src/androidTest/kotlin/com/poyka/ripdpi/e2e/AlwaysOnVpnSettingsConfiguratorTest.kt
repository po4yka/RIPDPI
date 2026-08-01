package com.poyka.ripdpi.e2e

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

private const val SettingsPackage = "com.android.settings"
private const val SettingsSubSettings = "com.android.settings.SubSettings"
private const val VpnAppManagementFragment = "com.android.settings.vpn2.AppManagementFragment"
private const val SettingsFragmentExtra = ":settings:show_fragment"
private const val SettingsFragmentArgumentsExtra = ":settings:show_fragment_args"
private const val SettingsFragmentTitleExtra = ":settings:show_fragment_title"
private const val SettingsSourceMetricsExtra = ":settings:source_metrics"
private const val SettingsVpnMetricsCategory = 2033
private const val SwitchResource = "android:id/switch_widget"
private const val PositiveButtonResource = "android:id/button1"
private const val UiTimeoutMs = 10_000L

@RunWith(AndroidJUnit4::class)
class AlwaysOnVpnSettingsConfiguratorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun configureAlwaysOnVpnThroughSettings() {
        val arguments = InstrumentationRegistry.getArguments()
        val packageName =
            requireNotNull(arguments.getString("ripdpi.alwaysOnPackage")) {
                "Missing ripdpi.alwaysOnPackage"
            }
        require(packageName.matches(Regex("[A-Za-z0-9._]+"))) {
            "Invalid VPN package name"
        }
        val enabled = arguments.requiredBoolean("ripdpi.alwaysOnEnabled")
        val lockdown = arguments.requiredBoolean("ripdpi.alwaysOnLockdown")
        require(enabled || !lockdown) { "Lockdown requires always-on VPN" }

        openVpnAppManagement(packageName)
        setProfileState(enabled = enabled, lockdown = lockdown)

        val expectedPackage = if (enabled) packageName else "null"
        val expectedLockdown = if (lockdown) "1" else "0"
        awaitUntil(timeoutMs = UiTimeoutMs) {
            execShell("settings get secure always_on_vpn_app").trim() == expectedPackage &&
                execShell("settings get secure always_on_vpn_lockdown").trim() == expectedLockdown
        }
        assertEquals(expectedPackage, execShell("settings get secure always_on_vpn_app").trim())
        assertEquals(expectedLockdown, execShell("settings get secure always_on_vpn_lockdown").trim())
        device.pressHome()
    }

    private fun openVpnAppManagement(packageName: String) {
        val fragmentArguments = Bundle().apply { putString("package", packageName) }
        val intent =
            Intent(Intent.ACTION_MAIN)
                .setClassName(SettingsPackage, SettingsSubSettings)
                .putExtra(SettingsFragmentExtra, VpnAppManagementFragment)
                .putExtra(SettingsFragmentArgumentsExtra, fragmentArguments)
                .putExtra(SettingsFragmentTitleExtra, packageName)
                .putExtra(SettingsSourceMetricsExtra, SettingsVpnMetricsCategory)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ANY_ACTIVITY")
        try {
            instrumentation.targetContext.startActivity(intent)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }

        check(device.wait(Until.hasObject(By.pkg(SettingsPackage)), UiTimeoutMs)) {
            "Android VPN app management did not become visible"
        }
        awaitUntil(timeoutMs = UiTimeoutMs) { visiblePreferenceSwitches().size == 2 }
    }

    private fun setProfileState(
        enabled: Boolean,
        lockdown: Boolean,
    ) {
        var switches = visiblePreferenceSwitches()
        val alwaysOn = switches[0]
        if (alwaysOn.isChecked != enabled) {
            alwaysOn.click()
            confirmIfRequested()
            awaitUntil(timeoutMs = UiTimeoutMs) {
                visiblePreferenceSwitches()[0].isChecked == enabled
            }
        }

        if (!enabled) return

        switches = visiblePreferenceSwitches()
        val lockdownSwitch = switches[1]
        if (lockdownSwitch.isChecked != lockdown) {
            lockdownSwitch.click()
            confirmIfRequested()
            awaitUntil(timeoutMs = UiTimeoutMs) {
                visiblePreferenceSwitches()[1].isChecked == lockdown
            }
        }
    }

    private fun visiblePreferenceSwitches(): List<UiObject2> =
        device
            .findObjects(By.res(SwitchResource))
            .filter { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }
            .sortedBy { it.visibleBounds.top }
            .also { switches ->
                check(switches.size == 2) {
                    "Expected exactly two visible VPN management switches, found ${switches.size}"
                }
            }

    private fun confirmIfRequested() {
        device.wait(Until.findObject(By.res(PositiveButtonResource)), 1_000)?.click()
    }
}

private fun Bundle.requiredBoolean(key: String): Boolean =
    when (val value = getString(key)) {
        "true" -> true
        "false" -> false
        else -> error("Missing or invalid $key: $value")
    }
