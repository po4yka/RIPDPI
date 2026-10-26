package com.poyka.ripdpi.security

import android.app.KeyguardManager
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
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
        setGate { _, _ ->
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

    private fun setGate(prompt: SecretRevealPrompt) {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).get()
        composeRule.setContent {
            CompositionLocalProvider(LocalActivity provides activity) {
                RipDpiTheme {
                    val reveal = rememberSecretRevealGate(onAuthorized = { reveals++ }, prompt = prompt)
                    Button(onClick = reveal) { Text(RevealLabel) }
                }
            }
        }
    }

    private fun tapReveal() {
        composeRule.onNodeWithText(RevealLabel).performClick()
        composeRule.waitForIdle()
    }

    private companion object {
        const val RevealLabel = "Reveal"
    }
}
