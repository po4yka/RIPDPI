package com.poyka.ripdpi.diagnostics.replay

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/**
 * OkHttp-backed [ProbeReplayService]. Runs a HEAD request through an
 * [OkHttpClient] supplied by [ReplayHttpClientFactory] (the caller is
 * responsible for wiring it through the local SOCKS5 proxy at
 * 127.0.0.1:1080), instrumenting each protocol boundary via
 * [EventListener] and emitting [ReplayStepEvent] as each step
 * transitions.
 *
 * Cancel-safety: cancellation of the collecting coroutine cancels the
 * underlying OkHttp [Call] via [suspendCancellableCoroutine.invokeOnCancellation],
 * mirroring `OkHttpStrategyProbeTransport` in StrategyProbeService.kt:299-344.
 * The async [Call.enqueue] callback path is used (NOT the blocking
 * [Call.execute]) so cancellation propagates synchronously through
 * [Call.cancel] without depending on `Dispatchers.IO` thread interruption.
 *
 * Strategy activation: callers must wrap the [run] call in their own
 * activate-finally-restore block per StrategyProbeService.kt:201-234.
 * This service does NOT mutate AppSettings.
 *
 * Design ref: docs/architecture/G008_SUBSYSTEMS_DESIGN.md P4
 * implementation-plan item 2.
 */
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

                // Drain the listener channel into the flow as events arrive.
                val drainer =
                    launch {
                        for (event in events) {
                            send(event)
                        }
                    }

                // cancel-safe: Call is dispatched asynchronously; cancellation
                // of the collector cancels the suspended coroutine, which
                // invokes Call.cancel() via invokeOnCancellation. OkHttp
                // surfaces the cancel as an IOException on Callback.onFailure,
                // but we already resumed-on-cancellation, so onFailure is a
                // no-op against an inactive continuation.
                val result: Result<Response> =
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(
                            object : Callback {
                                override fun onResponse(
                                    call: Call,
                                    response: Response,
                                ) {
                                    if (!continuation.isActive) {
                                        response.close()
                                        return
                                    }
                                    continuation.resume(Result.success(response))
                                }

                                override fun onFailure(
                                    call: Call,
                                    e: IOException,
                                ) {
                                    if (!continuation.isActive) {
                                        return
                                    }
                                    continuation.resume(Result.failure(e))
                                }
                            },
                        )
                    }

                // Drop the response body — we issued a HEAD, but be defensive.
                result.getOrNull()?.close()

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

/**
 * The OkHttp pair the replay engine needs: a client wired with the
 * caller's preferred [EventListener] + timeouts and the absolute URL
 * to fetch (e.g. `https://example.com/`). The factory owns URL
 * construction so production wiring can target the live SOCKS5 proxy
 * at 127.0.0.1:1080 while tests route through MockWebServer's port.
 */
data class ReplayHttpClient(
    val client: OkHttpClient,
    val url: HttpUrl,
)

/**
 * Builds the [OkHttpClient] + URL pair for a single replay run. The
 * factory must apply the request's `timeoutMs` to connect / read /
 * write / call timeouts and attach the supplied [EventListener]. Tests
 * substitute MockWebServer-routed clients; production wiring lands in
 * P4.4 via the diagnostics module.
 */
fun interface ReplayHttpClientFactory {
    fun build(
        request: ReplayProbeRequest,
        listener: EventListener,
    ): ReplayHttpClient
}

/**
 * EventListener that translates OkHttp callbacks into ReplayStepEvent
 * emissions. Tracks the most-recently-started step so the terminal
 * event can attribute failure correctly.
 */
private class ReplayEventListener(
    private val channel: Channel<ReplayStepEvent>,
) : EventListener() {
    @Volatile private var currentStep: ReplayStepKind? = null

    @Volatile private var lastSuccessfulStep: ReplayStepKind? = null

    @Volatile private var lastErrorKind: ReplayErrorKind? = null
    private val stepStartedAtMs = mutableMapOf<ReplayStepKind, Long>()

    fun terminalStep(): ReplayStepKind? = currentStep ?: lastSuccessfulStep

    fun lastErrorKind(): ReplayErrorKind? = lastErrorKind

    private fun emit(event: ReplayStepEvent) {
        // UNLIMITED channel never fails to accept; ignore the Result.
        channel.trySend(event)
    }

    private fun start(step: ReplayStepKind) {
        currentStep = step
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
        // OkHttp emits no separate "ClientHello sent" callback. Treat the
        // ClientHello step as an instantaneous boundary inferred at
        // secureConnectStart so the UI can show the ordered progression.
        // The actual TLS handshake spans until secureConnectEnd.
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

    /**
     * When a call fails with no in-flight step (currentStep == null), the
     * fault almost always belongs to the next protocol boundary after the
     * last successfully completed one — e.g. handshake-failed surfaces in
     * callFailed without re-entering secureConnectStart. This helper maps
     * "last good" → "what was about to start" so the UI attribution is
     * correct.
     */
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
