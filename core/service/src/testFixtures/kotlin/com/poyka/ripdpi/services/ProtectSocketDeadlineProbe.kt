package com.poyka.ripdpi.services

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import com.poyka.ripdpi.service.session.vpn.vpnProtectSocketPath
import java.io.File

/** Uses real Android UDS I/O; denial is intentional and does not test VPN routing. */
class ProtectSocketDeadlineProbe {
    data class Result(
        val silentClientResults: List<Int>,
        val nextRequestAck: Int,
    )

    fun run(filesDir: File): Result {
        val server =
            VpnProtectSocketServer(
                vpnProtectSocketPath(filesDir),
                InMemoryVpnProtectFailureMonitor(),
                fdProtector = { false },
            )
        try {
            server.start()
            return connect(server.socketPath).use { first ->
                connect(server.socketPath).use { second ->
                    val results = listOf(first.inputStream.read(), second.inputStream.read())
                    Result(results, request(server.socketPath))
                }
            }
        } finally {
            server.stop()
        }
    }

    private fun connect(path: String): LocalSocket =
        LocalSocket().also { socket ->
            try {
                socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = 4_000
            } catch (error: Exception) {
                socket.close()
                throw error
            }
        }

    private fun request(path: String): Int {
        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        try {
            return connect(path).use { socket ->
                socket.setFileDescriptorsForSend(arrayOf(fd))
                socket.outputStream.write(1)
                socket.inputStream.read()
            }
        } finally {
            Os.close(fd)
        }
    }
}
