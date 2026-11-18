package com.poyka.ripdpi.services

import android.app.Service
import android.content.Intent
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.startAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StaleServiceStartTest {
    @Test
    fun `stale start stops only the idle service for either mode`() =
        runBlocking {
            for (mode in listOf(Mode.VPN, Mode.Proxy)) {
                for (ownsPause in listOf(false, true)) {
                    val fixture = PausedServiceReconstructionFixture(mode)
                    val controller =
                        when (mode) {
                            Mode.VPN -> Robolectric.buildService(RipDpiVpnService::class.java)
                            Mode.Proxy -> Robolectric.buildService(RipDpiProxyService::class.java)
                        }
                    val service: Service = controller.get()
                    val scope =
                        when (service) {
                            is RipDpiVpnService -> {
                                initializePausedVpnService(service, fixture)
                                service.serviceScope
                            }

                            is RipDpiProxyService -> {
                                initializePausedProxyService(service, fixture)
                                service.serviceScope
                            }

                            else -> {
                                error("Unknown service")
                            }
                        }
                    val oldGeneration = fixture.authority.reference().generation
                    if (!ownsPause) fixture.authority.reserveStart(mode)
                    val stale = Intent(startAction).putExtra(durableIntentGenerationExtra, oldGeneration - 1)
                    controller.create()
                    try {
                        val parent = checkNotNull(scope.coroutineContext[Job])
                        val existingJobs = parent.children.toSet()
                        service.onStartCommand(stale, 0, 7)
                        withTimeout(5_000) {
                            parent.children
                                .filterNot(existingJobs::contains)
                                .toList()
                                .joinAll()
                        }
                        assertEquals("mode=$mode paused=$ownsPause", !ownsPause, shadowOf(service).isStoppedBySelf)
                    } finally {
                        controller.destroy()
                        awaitPausedServiceDestruction(service)
                    }
                }
            }
        }
}
