package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.LocalSocksConnectRejectedException
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class RelayProbeFailureStageTest {
    @Test
    fun `typed probe failures map to their field-report stage`() {
        val cases =
            listOf(
                UnknownHostException("relay.example") to FieldFailureStage.Dns,
                IOException("wrapped", SSLHandshakeException("handshake")) to FieldFailureStage.TlsHandshake,
                SocketTimeoutException("read") to FieldFailureStage.IdleTimeout,
                ConnectException("refused") to FieldFailureStage.TcpConnect,
                IOException("socks", LocalSocksConnectRejectedException(4)) to FieldFailureStage.TcpConnect,
                LocalSocksConnectRejectedException(5) to FieldFailureStage.TcpConnect,
                LocalSocksConnectRejectedException(6) to FieldFailureStage.IdleTimeout,
                LocalSocksConnectRejectedException(1) to FieldFailureStage.Unknown,
                // Message text never decides the stage.
                IOException("connection refused by dns tls handshake") to FieldFailureStage.Unknown,
            )

        cases.forEach { (error, stage) -> assertEquals(error.toString(), stage, relayProbeFailureStage(error)) }
    }
}
