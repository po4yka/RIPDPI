@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.poyka.ripdpi.core.detection.probe

import com.google.protobuf.InvalidProtocolBufferException
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.xray.app.proxyman.command.HandlerServiceGrpc
import com.xray.app.proxyman.command.ListOutboundsRequest
import com.xray.common.net.IPOrDomain
import com.xray.common.serial.TypedMessage
import com.xray.transport.internet.StreamConfig
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
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
    suspend fun listOutbounds(
        port: Int,
        deadlineMs: Long = 600,
    ): Result<XrayApiScanResult> =
        withContext(dispatchers.io) {
            val channel =
                OkHttpChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .build()

            try {
                val stub =
                    HandlerServiceGrpc
                        .newBlockingStub(channel)
                        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

                val response = stub.listOutbounds(ListOutboundsRequest.getDefaultInstance())
                val outbounds =
                    response.outboundsList
                        .filterNot { outbound ->
                            outbound.proxySettings.type == "xray.proxy.freedom.Config" ||
                                outbound.proxySettings.type == "xray.proxy.blackhole.Config"
                        }.map { outbound ->
                            val tag = outbound.tag.takeIf { it.isNotBlank() } ?: "(untagged)"
                            val senderType = outbound.senderSettings.type.takeIf { it.isNotBlank() }
                            val proxyType = outbound.proxySettings.type.takeIf { it.isNotBlank() }

                            val senderParsed =
                                parseSenderSettings(
                                    outbound.senderSettings.type,
                                    outbound.senderSettings.value,
                                )
                            val vlessParsed =
                                parseVlessProxySettings(
                                    outbound.proxySettings.type,
                                    outbound.proxySettings.value,
                                )

                            XrayOutboundSummary(
                                tag = tag,
                                protocolName = senderParsed.protocolName,
                                address = vlessParsed.address,
                                port = vlessParsed.port,
                                uuidPresent = vlessParsed.uuidPresent,
                                sni = senderParsed.sni,
                                publicKeyPresent = senderParsed.publicKeyPresent,
                                senderSettingsType = senderType,
                                proxySettingsType = proxyType,
                            )
                        }

                Result.success(
                    XrayApiScanResult(
                        endpoint = XrayApiEndpoint(host = host, port = port),
                        outbounds = outbounds,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                channel.shutdownNow()
                channel.awaitTermination(100, TimeUnit.MILLISECONDS)
            }
        }

    internal data class SenderParsed(
        val protocolName: String?,
        val sni: String?,
        val publicKeyPresent: Boolean,
    )

    internal data class VlessParsed(
        val address: String?,
        val port: Int?,
        val uuidPresent: Boolean,
    )

    @Suppress("TooGenericExceptionCaught")
    internal fun parseSenderSettings(
        type: String,
        value: com.google.protobuf.ByteString,
    ): SenderParsed {
        if (type != "xray.app.proxyman.SenderConfig") {
            return SenderParsed(protocolName = null, sni = null, publicKeyPresent = false)
        }

        return try {
            val sender = ProxymanSenderConfig.parseFrom(value)
            parseSenderDetails(sender.streamSettings)
        } catch (_: Exception) {
            SenderParsed(protocolName = null, sni = null, publicKeyPresent = false)
        }
    }

    private fun parseSenderDetails(stream: StreamConfig): SenderParsed {
        var sni: String? = null
        var publicKeyPresent = false
        for (security in stream.securitySettingsList) {
            val reality = parseRealitySettings(security)
            if (reality != null) {
                if (sni == null) sni = reality.serverName.takeIf { it.isNotBlank() }
                publicKeyPresent = publicKeyPresent || reality.publicKey.size() > 0
            }
        }
        return SenderParsed(
            protocolName = stream.protocolName.takeIf { it.isNotBlank() },
            sni = sni,
            publicKeyPresent = publicKeyPresent,
        )
    }

    private fun parseRealitySettings(security: TypedMessage): RealityConfig? {
        if (security.type != "xray.transport.internet.reality.Config") return null
        return try {
            RealityConfig.parseFrom(security.value)
        } catch (_: InvalidProtocolBufferException) {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun parseVlessProxySettings(
        type: String,
        value: com.google.protobuf.ByteString,
    ): VlessParsed {
        if (type != "xray.proxy.vless.outbound.Config") {
            return VlessParsed(address = null, port = null, uuidPresent = false)
        }

        return try {
            val config = VlessOutboundConfig.parseFrom(value)
            val vnext = config.vnext
            val address = ipOrDomainToString(vnext.address)
            val port = vnext.port
            val user = vnext.user

            var uuidPresent = false
            val account = user.account
            if (account.type == "xray.proxy.vless.Account") {
                uuidPresent =
                    try {
                        VlessAccount.parseFrom(account.value).id.isNotBlank()
                    } catch (_: InvalidProtocolBufferException) {
                        false
                    }
            }

            VlessParsed(address = address, port = port, uuidPresent = uuidPresent)
        } catch (_: Exception) {
            VlessParsed(address = null, port = null, uuidPresent = false)
        }
    }

    private fun ipOrDomainToString(value: IPOrDomain): String? =
        when (value.addressCase) {
            IPOrDomain.AddressCase.DOMAIN -> {
                value.domain.takeIf { it.isNotBlank() }
            }

            IPOrDomain.AddressCase.IP -> {
                try {
                    InetAddress.getByAddress(value.ip.toByteArray()).hostAddress
                } catch (_: Exception) {
                    null
                }
            }

            else -> {
                null
            }
        }
}
