package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DefaultDeviceRuntimeEvidenceStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnServiceRevokeDuringAttachTest {
    @Test
    fun `revoke while the session is still attaching does not crash the main thread`() {
        val service = Robolectric.buildService(RipDpiVpnService::class.java).get()
        service.hardKillSwitchStateStore =
            SharedPreferencesAndroidHardKillSwitchStateStore(RuntimeEnvironment.getApplication())
        service.runtimeEvidenceReporter =
            AndroidRuntimeEvidenceReporter(DefaultDeviceRuntimeEvidenceStore(), AndroidRuntimeEvidenceClock { 1_000 })
        // ensureActiveSession registers its first owned resource before it creates the shell delegate.
        ReflectionHelpers.getField<ActiveSessionOwnership>(service, "activeOwnership").own {}

        service.onRevoke()
    }
}
