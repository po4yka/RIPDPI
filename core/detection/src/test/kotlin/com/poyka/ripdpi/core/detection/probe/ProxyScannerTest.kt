package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

class ProxyScannerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val dispatchers =
        AppCoroutineDispatchers(
            io = Dispatchers.IO,
            default = Dispatchers.Default,
            main = Dispatchers.Default,
        )

    @Test
    fun popularPortOutsideSelectedRangeIsNotOpened() {
        ServerSocket(0, 1, loopback).use { popular ->
            ServerSocket(0, 1, loopback).use { selected ->
                val result =
                    runBlocking {
                        ProxyScanner(
                            dispatchers = dispatchers,
                            loopbackHosts = listOf(loopback.hostAddress),
                            popularPorts = listOf(popular.localPort),
                            scanRange = selected.localPort..selected.localPort,
                            readTimeoutMs = 30,
                            maxConcurrency = 1,
                        ).findOpenProxyEndpoint {}
                    }

                assertNull(result)
                popular.soTimeout = 100
                assertThrows(SocketTimeoutException::class.java) { popular.accept().close() }
            }
        }
    }
}
