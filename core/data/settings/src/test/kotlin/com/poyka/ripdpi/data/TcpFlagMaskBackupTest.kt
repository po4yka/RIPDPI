package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TcpFlagMaskBackupTest {
    private val stored =
        AppSettings
            .newBuilder()
            .addTcpChainSteps(
                StrategyTcpStep
                    .newBuilder()
                    .setKind("fake")
                    .setMarker("1")
                    .setTcpFlagsSet("syn"),
            ).build()

    @Test
    fun `valid TCP flag masks survive the JSON backup`() {
        assertEquals("syn", appSettingsFromJson(stored.toJson()).getTcpChainSteps(0).tcpFlagsSet)
    }

    @Test
    fun `backup with an unknown TCP flag is rejected before it can be stored`() {
        val json = stored.toJson()
        val poisoned = json.replace(Regex("(\"tcpFlagsSet\"\\s*:\\s*)\"syn\""), "$1\"ns|syn\"")
        check(poisoned != json) { "fixture did not contain the TCP flag mask" }

        assertThrows(IllegalArgumentException::class.java) { appSettingsFromJson(poisoned) }
    }
}
