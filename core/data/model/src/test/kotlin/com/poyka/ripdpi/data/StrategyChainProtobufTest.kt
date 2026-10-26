package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyChainProtobufTest {
    @Test
    fun `stored step with an unknown TCP flag is dropped instead of failing the read`() {
        val settings =
            AppSettings
                .newBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fake")
                        .setMarker("1")
                        .setTcpFlagsSet("ns|syn"),
                ).addTcpChainSteps(StrategyTcpStep.newBuilder().setKind("split").setMarker("1"))
                .build()

        assertEquals(listOf(TcpChainStepKind.Split), settings.effectiveTcpChainSteps().map { it.kind })
    }
}
