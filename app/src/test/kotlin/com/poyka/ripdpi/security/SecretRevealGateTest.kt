package com.poyka.ripdpi.security

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.fragment.app.FragmentActivity
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecretRevealGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private var reveals = 0

    @Test
    fun `prompt result success reveals once and other results keep the secret hidden`() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        val results =
            ArrayDeque(
                listOf(
                    BiometricAuthResult.Failed,
                    BiometricAuthResult.Cancelled,
                    BiometricAuthResult.Error(errorCode = 7, message = "lockout"),
                    BiometricAuthResult.Success,
                ),
            )
        var prompts = 0
        setGate(appPin = null) { _, _ ->
            prompts++
            results.removeFirst()
        }

        repeat(3) { tapReveal() }
        composeRule.runOnIdle {
            assertEquals(3, prompts)
            assertEquals("a failed, cancelled or failing prompt must not reveal", 0, reveals)
        }

        tapReveal()
        composeRule.runOnIdle {
            assertEquals(4, prompts)
            assertEquals(1, reveals)
        }
    }

    @Test
    fun `without a screen lock the app backup PIN is required before a reveal`() {
        removeDeviceLock()
        val appPin = FakeAppPin(hasPin = true)
        var prompts = 0
        setGate(appPin = appPin) { _, _ ->
            prompts++
            BiometricAuthResult.Success
        }

        tapReveal()
        composeRule.onNodeWithTag(RipDpiTestTags.BiometricPromptPinField).performTextInput("0000")
        composeRule.onNodeWithTag(RipDpiTestTags.BiometricPromptPrimaryAction).performClick()
        composeRule.runOnIdle { assertEquals("a wrong PIN must not reveal", 0, reveals) }

        composeRule.onNodeWithTag(RipDpiTestTags.BiometricPromptPinField).performTextReplacement("1234")
        composeRule.onNodeWithTag(RipDpiTestTags.BiometricPromptPrimaryAction).performClick()
        composeRule.runOnIdle {
            assertEquals(1, reveals)
            assertEquals(listOf("0000", "1234"), appPin.attempts)
            assertEquals("no system prompt without a device lock", 0, prompts)
        }
    }

    @Test
    fun `without a screen lock or an app PIN the reveal needs no prompt`() {
        removeDeviceLock()
        setGate(appPin = FakeAppPin(hasPin = false)) { _, _ -> error("no prompt expected") }

        tapReveal()

        composeRule.runOnIdle { assertEquals(1, reveals) }
    }

    private fun setGate(
        appPin: AppPinCheck?,
        prompt: SecretRevealPrompt,
    ) {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).get()
        composeRule.setContent {
            CompositionLocalProvider(LocalActivity provides activity) {
                RipDpiTheme {
                    val reveal =
                        rememberSecretRevealGate(onAuthorized = { reveals++ }, appPin = appPin, prompt = prompt)
                    Button(onClick = reveal) { Text(RevealLabel) }
                }
            }
        }
    }

    private fun tapReveal() {
        composeRule.onNodeWithText(RevealLabel).performClick()
        composeRule.waitForIdle()
    }

    private fun removeDeviceLock() {
        Shadow
            .extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java))
            .setCanAuthenticate(false)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(false)
    }

    private class FakeAppPin(
        private val hasPin: Boolean,
    ) : AppPinCheck {
        val attempts = mutableListOf<String>()

        override suspend fun hasPin(): Boolean = hasPin

        override suspend fun verify(pin: String): PinVerifyResult {
            attempts += pin
            return if (pin == "1234") PinVerifyResult.Success else PinVerifyResult.Failed
        }
    }

    private companion object {
        const val RevealLabel = "Reveal"
    }
}
