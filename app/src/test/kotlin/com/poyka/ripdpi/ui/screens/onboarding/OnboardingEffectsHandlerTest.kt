package com.poyka.ripdpi.ui.screens.onboarding

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.activities.OnboardingEffect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingEffectsHandlerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `notifications effect triggers notifications callback`() {
        val effects = MutableSharedFlow<OnboardingEffect>(extraBufferCapacity = 1)
        var notificationsRequests = 0

        composeRule.setContent {
            OnboardingEffectsHandler(
                effects = effects,
                onComplete = {},
                onRequestNotificationsPermission = { notificationsRequests += 1 },
                onRequestNotificationsSettings = {},
                onRequestVpnConsent = {},
            )
        }

        runBlocking {
            effects.emit(OnboardingEffect.RequestNotificationsPermission)
        }
        composeRule.waitForIdle()

        assertEquals(1, notificationsRequests)
    }

    @Test
    fun `vpn consent effect forwards intent to callback`() {
        val effects = MutableSharedFlow<OnboardingEffect>(extraBufferCapacity = 1)
        var launchedIntent: Intent? = null

        composeRule.setContent {
            OnboardingEffectsHandler(
                effects = effects,
                onComplete = {},
                onRequestNotificationsPermission = {},
                onRequestNotificationsSettings = {},
                onRequestVpnConsent = { launchedIntent = it },
            )
        }

        val intent = Intent("test.vpn")
        runBlocking {
            effects.emit(OnboardingEffect.RequestVpnConsent(intent))
        }
        composeRule.waitForIdle()

        assertEquals(intent, launchedIntent)
    }

    @Test
    fun `notification settings effect forwards intent to callback`() {
        val effects = MutableSharedFlow<OnboardingEffect>(extraBufferCapacity = 1)
        var launchedIntent: Intent? = null

        composeRule.setContent {
            OnboardingEffectsHandler(
                effects = effects,
                onComplete = {},
                onRequestNotificationsPermission = {},
                onRequestNotificationsSettings = { launchedIntent = it },
                onRequestVpnConsent = { launchedIntent = it },
            )
        }

        val intent = Intent("test.notification.settings")
        runBlocking {
            effects.emit(OnboardingEffect.RequestNotificationsSettings(intent))
        }
        composeRule.waitForIdle()

        assertEquals(intent, launchedIntent)
    }
}
