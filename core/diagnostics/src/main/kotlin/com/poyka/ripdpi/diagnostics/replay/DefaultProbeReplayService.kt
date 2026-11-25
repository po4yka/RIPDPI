package com.poyka.ripdpi.diagnostics.replay

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

class DefaultProbeReplayService
    @Inject
    constructor(
        private val clientFactory: ReplayHttpClientFactory,
        private val recommendationEngine: ReplayRecommendationEngine,
    ) : ProbeReplayService {
        override fun run(request: ReplayProbeRequest): Flow<ReplayStepEvent> =
            channelFlow {
                val events = Channel<ReplayStepEvent>(capacity = Channel.UNLIMITED)
                val listener = ReplayEventListener(events)
                val replayClient = clientFactory.build(request, listener)
                val httpRequest =
                    Request
                        .Builder()
                        .url(replayClient.url)
                        .head()
                        .build()
                val call = replayClient.client.newCall(httpRequest)

                val drainer =
                    launch {
                        for (event in events) {
                            send(event)
                        }
                    }

                val result =
                    try {
                        call.executeAsync().use { }
                        Result.success(Unit)
                    } catch (error: IOException) {
                        Result.failure(error)
                    }

                val verdict =
                    result.fold(
                        onSuccess = { ReplayVerdict.Success },
                        onFailure = { ReplayVerdict.Failure },
                    )
                val terminalStep = listener.terminalStep()
                val recommendationKey =
                    if (verdict == ReplayVerdict.Failure) {
                        recommendationEngine.recommendationKeyFor(
                            terminalStep = terminalStep,
                            errorKind = listener.lastErrorKind(),
                        )
                    } else {
                        ""
                    }
                events.send(
                    ReplayStepEvent.Finished(
                        verdict = verdict,
                        terminalStep = terminalStep,
                        recommendationKey = recommendationKey,
                    ),
                )
                events.close()
                drainer.join()
            }
    }

data class ReplayHttpClient(
    val client: OkHttpClient,
    val url: HttpUrl,
)

fun interface ReplayHttpClientFactory {
    suspend fun build(
        request: ReplayProbeRequest,
        listener: EventListener,
    ): ReplayHttpClient
}

private class ReplayEventListener(
    private val channel: Channel<ReplayStepEvent>,
) : EventListener() {
    @Volatile private var currentStep: ReplayStepKind? = null

    @Volatile private var lastSuccessfulStep: ReplayStepKind? = null

    @Volatile private var lastErrorKind: ReplayErrorKind? = null

    @Volatile private var failedStep: ReplayStepKind? = null
    private val stepStartedAtMs = mutableMapOf<ReplayStepKind, Long>()

    fun terminalStep(): ReplayStepKind? = failedStep ?: currentStep ?: lastSuccessfulStep

    fun lastErrorKind(): ReplayErrorKind? = lastErrorKind

    private fun emit(event: ReplayStepEvent) {
        channel.trySend(event)
    }

    private fun start(step: ReplayStepKind) {
        currentStep = step
        failedStep = null
        val now = System.currentTimeMillis()
        stepStartedAtMs[step] = now
        emit(ReplayStepEvent.StepStarted(step, now))
    }

    private fun complete(
        step: ReplayStepKind,
        detail: String,
    ) {
        val started = stepStartedAtMs[step] ?: System.currentTimeMillis()
        val duration = System.currentTimeMillis() - started
        emit(ReplayStepEvent.StepCompleted(step, duration, detail))
        lastSuccessfulStep = step
        currentStep = null
    }

    private fun fail(
        step: ReplayStepKind,
        errorKind: ReplayErrorKind,
        detail: String,
    ) {
        emit(ReplayStepEvent.StepFailed(step, errorKind, detail))
        lastErrorKind = errorKind
        failedStep = step
        currentStep = null
    }

    override fun dnsStart(
        call: Call,
        domainName: String,
    ) {
        start(ReplayStepKind.DnsResolve)
    }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) {
        complete(ReplayStepKind.DnsResolve, "resolved ${inetAddressList.size} address(es)")
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        start(ReplayStepKind.TcpOpen)
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        complete(ReplayStepKind.TcpOpen, "TCP open")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        val step = currentStep ?: ReplayStepKind.TcpOpen
        fail(step, classify(ioe), ioe.message ?: ioe.javaClass.simpleName)
    }

    override fun secureConnectStart(call: Call) {
        start(ReplayStepKind.TlsClientHello)
        complete(ReplayStepKind.TlsClientHello, "ClientHello sent")
        start(ReplayStepKind.TlsHandshake)
    }

    override fun secureConnectEnd(
        call: Call,
        handshake: Handshake?,
    ) {
        complete(ReplayStepKind.TlsHandshake, "TLS handshake complete")
    }

    override fun responseHeadersEnd(
        call: Call,
        response: Response,
    ) {
        start(ReplayStepKind.FirstByte)
        complete(ReplayStepKind.FirstByte, "HTTP ${response.code}")
    }

    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) {
        val step = currentStep ?: lastSuccessfulStep?.next() ?: ReplayStepKind.DnsResolve
        fail(step, classify(ioe), ioe.message ?: ioe.javaClass.simpleName)
    }

    private fun ReplayStepKind.next(): ReplayStepKind =
        when (this) {
            ReplayStepKind.DnsResolve -> ReplayStepKind.TcpOpen
            ReplayStepKind.TcpOpen -> ReplayStepKind.TlsClientHello
            ReplayStepKind.TlsClientHello -> ReplayStepKind.TlsHandshake
            ReplayStepKind.TlsHandshake -> ReplayStepKind.FirstByte
            ReplayStepKind.FirstByte -> ReplayStepKind.FirstByte
        }

    private fun classify(t: Throwable): ReplayErrorKind {
        val msg = t.message.orEmpty().lowercase()
        return when {
            t is SocketTimeoutException -> {
                ReplayErrorKind.Timeout
            }

            t is SSLException -> {
                ReplayErrorKind.TlsHandshakeFailed
            }

            "connection refused" in msg -> {
                ReplayErrorKind.ConnectionRefused
            }

            "connection reset" in msg || "unexpected end of stream" in msg -> {
                ReplayErrorKind.ConnectionReset
            }

            t is UnknownHostException || "unknownhost" in msg || "no address" in msg -> {
                ReplayErrorKind.DnsTampered
            }

            else -> {
                ReplayErrorKind.Unknown
            }
        }
    }
}
