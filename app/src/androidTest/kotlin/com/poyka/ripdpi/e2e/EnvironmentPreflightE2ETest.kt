package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class EnvironmentPreflightE2ETest {
    @get:Rule(order = 0)
    val e2eFixtureRule = E2eFixtureRule()

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private var hiltInjected = false

    @Before
    fun setUp() {
        assumeE2eFixtureConfigured()
        hiltRule.inject()
        hiltInjected = true
        runBlocking {
            stopService(RipDpiProxyService::class.java)
            stopService(RipDpiVpnService::class.java)
        }
    }

    @After
    fun tearDown() {
        if (hiltInjected) {
            runBlocking {
                stopService(RipDpiProxyService::class.java)
                stopService(RipDpiVpnService::class.java)
            }
        }
    }

    @Test
    fun environmentSupportsFixtureReachabilityAndVpnConsent() {
        val environment = prepareE2eEnvironment(appContext, requireVpnConsent = true)
        assertTrue(environment.fixture.tcpEchoPort > 0)
        assertTrue(environment.fixture.dnsHttpPort > 0)
    }

    private fun stopService(serviceClass: Class<*>) {
        appContext.startService(Intent(appContext, serviceClass).setAction(stopAction))
    }
}
