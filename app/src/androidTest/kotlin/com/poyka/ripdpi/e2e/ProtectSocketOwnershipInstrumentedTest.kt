package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ActiveProtectSocketPathProvider
import com.poyka.ripdpi.services.ProtectSocketOwnershipProbe
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
class ProtectSocketOwnershipInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionRule: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { statement, _ -> statement }
        }

    @Inject lateinit var controller: ServiceController

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var state: ServiceStateStore

    @Inject lateinit var protection: ActiveProtectSocketPathProvider
    private lateinit var original: AppSettings
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            controller.stop()
            original = settings.snapshot()
        }
        ensureVpnConsentGranted(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            controller.stop()
            if (this@ProtectSocketOwnershipInstrumentedTest::original.isInitialized) settings.replace(original)
        }
        awaitUntil { protection.current() == null }
    }

    @Test
    fun oldCleanupPreservesRealReplacementProtectEndpoint() {
        assertTrue(runBlocking { controller.start(Mode.VPN) } is ServiceStartResult.Accepted)
        awaitServiceStatus(state, AppStatus.Running, Mode.VPN)
        val result = ProtectSocketOwnershipProbe().run(context.filesDir, protection)
        val report =
            JSONObject()
                .apply {
                    put("distinctPaths", result.distinctPaths)
                    put("replacementAckBeforeCleanup", result.replacementAckBeforeCleanup)
                    put("replacementAckAfterCleanup", result.replacementAckAfterCleanup)
                    put("noDescriptorAck", result.noDescriptorAck)
                    put("successfulProtectCalls", result.successfulProtectCalls)
                    put("failedProtectCalls", result.failedProtectCalls)
                    put("replacementPathPresent", result.replacementPathPresent)
                    put("connectFailureType", result.connectFailureType)
                    put("duplicateBindRejected", result.duplicateBindRejected)
                    put("ownerAckAfterFailedBind", result.ownerAckAfterFailedBind)
                }.toString(2)
        File(context.filesDir, "protect-ownership-probe.json").writeText(report)
        Log.i("ProtectOwnershipProbe", report)
        assertEquals("Real SCM_RIGHTS protection before cleanup", 0, result.replacementAckBeforeCleanup)
        assertEquals("Old cleanup must preserve replacement ACK", 0, result.replacementAckAfterCleanup)
        assertTrue(result.distinctPaths)
        assertTrue(result.replacementPathPresent)
        assertEquals(1, result.noDescriptorAck)
        assertTrue(result.duplicateBindRejected)
        assertEquals(0, result.ownerAckAfterFailedBind)
        assertEquals(3, result.successfulProtectCalls)
        assertEquals(0, result.failedProtectCalls)
    }
}
