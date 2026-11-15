@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.poyka.ripdpi.core.detection.probe

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.xray.app.proxyman.command.HandlerServiceGrpc
import com.xray.app.proxyman.command.ListOutboundsRequest
import com.xray.common.net.IPOrDomain
import com.xray.core.OutboundHandlerConfig
import com.xray.transport.internet.StreamConfig
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.IDN
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import com.xray.app.proxyman.SenderConfig as ProxymanSenderConfig
import com.xray.proxy.vless.Account as VlessAccount
import com.xray.proxy.vless.outbound.Config as VlessOutboundConfig
import com.xray.transport.internet.reality.Config as RealityConfig

class XrayApiClient(
    private val host: String = "127.0.0.1",
    private val dispatchers: AppCoroutineDispatchers,
) {
    private val address = detectionLoopbackAddress(host)

    suspend fun listOutbounds(
        port: Int,
        deadlineMs: Long = 600,
    ): Result<XrayApiScanResult> =
        withContext(dispatchers.io) {
            require(port in 1..65535)
            require(deadlineMs > 0)
            val socketFactory = LoopbackSocketFactory(address, deadlineMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val channel =
                OkHttpChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .proxyDetector { null }
                    .socketFactory(socketFactory)
                    .maxInboundMessageSize(MaxResponseBytes)
                    .disableRetry()
                    .build()

            try {
                val stub =
                    HandlerServiceGrpc
                        .newFutureStub(channel)
                        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

                val response =
                    suspendCancellableCoroutine { continuation ->
                        val call = stub.listOutbounds(ListOutboundsRequest.getDefaultInstance())
                        continuation.invokeOnCancellation { call.cancel(false) }
                        call.addListener({ continuation.resumeWith(runCatching(call::get)) }, Runnable::run)
                    }
                val outbounds =
                    response.outboundsList
                        .asSequence()
                        .filterNot { outbound ->
                            outbound.proxySettings.type == "xray.proxy.freedom.Config" ||
                                outbound.proxySettings.type == "xray.proxy.blackhole.Config"
                        }.take(MaxOutbounds + 1)
                        .toList()
                Result.success(
                    XrayApiScanResult(
                        endpoint = XrayApiEndpoint(host = host, port = port),
                        outbounds =
                            outbounds.take(MaxOutbounds).map { outbound ->
                                ensureActive()
                                summarize(outbound)
                            },
                        outboundsTruncated = outbounds.size > MaxOutbounds,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                channel.shutdownNow()
                runCatching { socketFactory.close() }
            }
        }

    private fun summarize(outbound: OutboundHandlerConfig): XrayOutboundSummary {
        val sender = outbound.senderSettings
        val proxy = outbound.proxySettings
        val senderDetails = parseSenderSettings(sender.type, sender.value)
        val vlessDetails = parseVlessProxySettings(proxy.type, proxy.value)
        return XrayOutboundSummary(
            tag = outbound.tag.summaryText() ?: "(untagged)",
            transportName = senderDetails.transportName,
            address = vlessDetails.address,
            port = vlessDetails.port,
            uuidPresent = vlessDetails.uuidPresent,
            sni = senderDetails.sni,
            publicKeyPresent = senderDetails.publicKeyPresent,
            senderSettingsType = sender.type.summaryText(),
            proxySettingsType = proxy.type.summaryText(),
            settingsMalformed = senderDetails.malformed || vlessDetails.malformed,
        )
    }

    internal data class SenderParsed(
        val transportName: String? = null,
        val sni: String? = null,
        val publicKeyPresent: Boolean = false,
        val malformed: Boolean = false,
    )

    internal data class VlessParsed(
        val address: String? = null,
        val port: Int? = null,
        val uuidPresent: Boolean = false,
        val malformed: Boolean = false,
    )

    internal fun parseSenderSettings(
        type: String,
        value: ByteString,
    ): SenderParsed {
        if (type != "xray.app.proxyman.SenderConfig") return SenderParsed()
        return try {
            parseSenderDetails(ProxymanSenderConfig.parseFrom(value).streamSettings)
        } catch (_: InvalidProtocolBufferException) {
            SenderParsed(malformed = true)
        }
    }

    private fun parseSenderDetails(stream: StreamConfig): SenderParsed {
        val transportName = stream.protocolName.summaryText()
        if (stream.securityType != RealitySettingsType) return SenderParsed(transportName = transportName)

        val selected =
            stream.securitySettingsList.firstOrNull { it.type == stream.securityType }
                ?: return SenderParsed(transportName = transportName, malformed = true)
        return try {
            val reality = RealityConfig.parseFrom(selected.value)
            val sni = reality.serverName.endpointName()
            SenderParsed(
                transportName = transportName,
                sni = sni,
                publicKeyPresent = !reality.publicKey.isEmpty,
                malformed = reality.serverName.isNotEmpty() && sni == null,
            )
        } catch (_: InvalidProtocolBufferException) {
            SenderParsed(transportName = transportName, malformed = true)
        }
    }

    internal fun parseVlessProxySettings(
        type: String,
        value: ByteString,
    ): VlessParsed {
        if (type != "xray.proxy.vless.outbound.Config") return VlessParsed()
        return try {
            val vnext = VlessOutboundConfig.parseFrom(value).vnext
            val address = ipOrDomainToString(vnext.address)
            val port = vnext.port.takeIf { it in 1..65535 }
            val account = vnext.user.account
            val uuidPresent =
                account.type == "xray.proxy.vless.Account" &&
                    try {
                        VlessAccount.parseFrom(account.value).id.isNotBlank()
                    } catch (_: InvalidProtocolBufferException) {
                        false
                    }
            VlessParsed(
                address = address,
                port = port,
                uuidPresent = uuidPresent,
                malformed = address == null || port == null || !uuidPresent,
            )
        } catch (_: InvalidProtocolBufferException) {
            VlessParsed(malformed = true)
        }
    }

    private fun ipOrDomainToString(value: IPOrDomain): String? =
        when (value.addressCase) {
            IPOrDomain.AddressCase.DOMAIN -> {
                value.domain.endpointName()
            }

            IPOrDomain.AddressCase.IP -> {
                val ip = value.ip
                if (ip.size() == 4 || ip.size() == 16) InetAddress.getByAddress(ip.toByteArray()).hostAddress else null
            }

            else -> {
                null
            }
        }

    private fun String.endpointName(): String? {
        if (length !in 1..MaxSummaryText || any(Char::isWhitespace) || DisplayControlCharacters.containsMatchIn(this)) {
            return null
        }
        return try {
            val hostname = IDN.toASCII(this, IDN.USE_STD3_ASCII_RULES).removeSuffix(".")
            hostname.takeIf { it.length in 1..MaxHostnameLength }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun String.summaryText(): String? =
        take(MaxSummaryText).replace(DisplayControlCharacters, "").trim().takeIf(String::isNotEmpty)

    private companion object {
        const val MaxResponseBytes = 512 * 1024
        const val MaxOutbounds = 64
        const val MaxSummaryText = 256
        const val MaxHostnameLength = 253
        const val RealitySettingsType = "xray.transport.internet.reality.Config"
        val DisplayControlCharacters = Regex("""[\p{Cc}\p{Cf}]""")
    }
}
