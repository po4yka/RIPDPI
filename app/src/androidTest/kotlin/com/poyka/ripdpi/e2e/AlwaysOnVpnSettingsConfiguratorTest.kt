package com.poyka.ripdpi.e2e

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SettingsPackage = "com.android.settings"
private const val SettingsButtonResource = "settings_button"
private const val SwitchResource = "switchWidget"
private const val PositiveButtonResource = "android:id/button1"
private const val UiTimeoutMs = 10_000L

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AlwaysOnVpnSettingsConfiguratorTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

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
        val packageManager = instrumentation.targetContext.packageManager
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        val applicationLabel = packageManager.getApplicationLabel(applicationInfo).toString()
        val intent =
            Intent(Settings.ACTION_VPN_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        execShell("am force-stop $SettingsPackage")
        instrumentation.targetContext.startActivity(intent)

        check(device.wait(Until.hasObject(By.pkg(SettingsPackage)), UiTimeoutMs)) {
            "Android VPN settings did not become visible"
        }
        val applicationLabelObject =
            checkNotNull(device.wait(Until.findObject(By.text(applicationLabel)), UiTimeoutMs)) {
                "VPN application row did not become visible: $applicationLabel"
            }
        val settingsButton =
            checkNotNull(
                generateSequence(applicationLabelObject) { it.parent }
                    .mapNotNull { ancestor ->
                        ancestor.findObject(By.res(SettingsPackage, SettingsButtonResource))
                    }.firstOrNull(),
            ) {
                "VPN application settings button did not become visible: $applicationLabel"
            }
        settingsButton.click()

        awaitUntil(timeoutMs = UiTimeoutMs) { visiblePreferenceSwitches().size == 2 }
    }

    private fun setProfileState(
        enabled: Boolean,
        lockdown: Boolean,
    ) {
        var switches = requiredPreferenceSwitches()
        val alwaysOn = switches[0]
        if (alwaysOn.isChecked != enabled) {
            alwaysOn.click()
            confirmIfRequested()
            awaitUntil(timeoutMs = UiTimeoutMs) {
                visiblePreferenceSwitches().getOrNull(0)?.isChecked == enabled
            }
        }

        if (!enabled) return

        switches = requiredPreferenceSwitches()
        val lockdownSwitch = switches[1]
        if (lockdownSwitch.isChecked != lockdown) {
            lockdownSwitch.click()
            confirmIfRequested()
            awaitUntil(timeoutMs = UiTimeoutMs) {
                visiblePreferenceSwitches().getOrNull(1)?.isChecked == lockdown
            }
        }
    }

    private fun visiblePreferenceSwitches(): List<UiObject2> =
        device
            .findObjects(By.res(SettingsPackage, SwitchResource))
            .filter { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }
            .sortedBy { it.visibleBounds.top }

    private fun requiredPreferenceSwitches(): List<UiObject2> =
        visiblePreferenceSwitches().also { switches ->
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
