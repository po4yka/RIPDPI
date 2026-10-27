package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

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

    @Test
    fun `dropped stored step is logged by kind without its value`() {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        val logger = Logger.getLogger(StrategyChainReadLoggerName)
        logger.addHandler(handler)
        try {
            AppSettings
                .newBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fake")
                        .setMarker("1")
                        .setTcpFlagsSet("ns|syn"),
                ).build()
                .effectiveTcpChainSteps()
        } finally {
            logger.removeHandler(handler)
        }

        val record = records.single()
        assertEquals(Level.WARNING, record.level)
        assertEquals("Dropped stored TCP step kind=fake: IllegalArgumentException", record.message)
    }
}
