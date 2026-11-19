package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.LocalSocksConnectRejectedException
import com.poyka.ripdpi.data.diagnostics.FieldFailureStage
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val SocksReplyNetworkUnreachable = 3
private const val SocksReplyHostUnreachable = 4
private const val SocksReplyConnectionRefused = 5
private const val SocksReplyTtlExpired = 6
private const val MaxCauseDepth = 10

/**
 * The `client-field-report` stage of an HTTP probe that failed through a candidate relay listener, from the
 * exception types and the RFC 1928 CONNECT reply only; message text is never read. An unmapped failure is
 * [FieldFailureStage.Unknown].
 */
internal fun relayProbeFailureStage(error: IOException): FieldFailureStage {
    val chain = generateSequence<Throwable>(error) { it.cause }.take(MaxCauseDepth).toList()
    val socksReply = chain.firstNotNullOfOrNull { (it as? LocalSocksConnectRejectedException)?.reply }
    return when {
        socksReply != null -> socksReplyStage(socksReply)
        chain.any { it is UnknownHostException } -> FieldFailureStage.Dns
        chain.any { it is SSLException } -> FieldFailureStage.TlsHandshake
        chain.any { it is SocketTimeoutException } -> FieldFailureStage.IdleTimeout
        chain.any { it is ConnectException || it is NoRouteToHostException } -> FieldFailureStage.TcpConnect
        else -> FieldFailureStage.Unknown
    }
}

private fun socksReplyStage(reply: Int): FieldFailureStage =
    when (reply) {
        SocksReplyNetworkUnreachable, SocksReplyHostUnreachable, SocksReplyConnectionRefused -> {
            FieldFailureStage.TcpConnect
        }

        SocksReplyTtlExpired -> {
            FieldFailureStage.IdleTimeout
        }

        else -> {
            FieldFailureStage.Unknown
        }
    }
