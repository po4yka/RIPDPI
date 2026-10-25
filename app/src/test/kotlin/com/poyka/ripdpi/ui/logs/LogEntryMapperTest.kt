package com.poyka.ripdpi.ui.logs

import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.platform.LocalizedTimestampFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LogEntryMapperTest {
    private fun mapper(): LogEntryMapper {
        val context = RuntimeEnvironment.getApplication()
        return LogEntryMapper(AndroidStringResolver(context), LocalizedTimestampFormatter(context))
    }

    @Test
    @Config(qualifiers = "de")
    fun `service lifecycle line is one translated sentence`() {
        val started = mapper().serviceLifecycleEntry(Mode.VPN, started = true, LogSeverity.Info, createdAtMs = 1L)
        val stopped = mapper().serviceLifecycleEntry(Mode.Proxy, started = false, LogSeverity.Info, createdAtMs = 2L)

        assertEquals("VPN-Dienst gestartet", started.message)
        assertEquals("Proxy-Dienst gestoppt", stopped.message)
    }
}
