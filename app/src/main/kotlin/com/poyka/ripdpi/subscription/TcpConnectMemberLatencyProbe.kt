package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * Production [MemberLatencyProbe] that measures the TCP-connect round-trip to a
 * selector member's endpoint. A successful connect's wall time is the latency; an
 * unreachable member (connect failure / timeout) reports `null`.
 *
 * The connect targets the member's own server endpoint (the real proxy host),
 * which the app process reaches directly: the VPN tunnel excludes the app's own
 * UID from the TUN (see `.claude/rules/vpnservice-protect-invariant.md` and the
 * own-UID routing exclusion), so this measurement is not captured by the tunnel
 * and cannot loop. The `probeUrl` carried by the urltest policy is advisory here —
 * reachability of the member endpoint itself is the failover-relevant signal.
 */
@Singleton
class TcpConnectMemberLatencyProbe
    @Inject
    constructor() : MemberLatencyProbe {
        override suspend fun measure(
            profile: ProxyProfile,
            probeUrl: String,
        ): Long? {
            val endpoint = profile.endpoint() ?: return null
            return withContext(Dispatchers.IO) {
                runCatching {
                    var connected = false
                    val elapsed =
                        measureTimeMillis {
                            Socket().use { socket ->
                                socket.connect(
                                    InetSocketAddress(endpoint.host, endpoint.port),
                                    ConnectTimeoutMillis,
                                )
                                connected = true
                            }
                        }
                    if (connected) elapsed else null
                }.getOrElse { error ->
                    if (error is IOException) null else throw error
                }
            }
        }

        private companion object {
            const val ConnectTimeoutMillis = 3_000
        }
    }

/** A member's reachable network endpoint, or `null` for kinds without a routable host (e.g. RawConfig). */
internal data class MemberEndpoint(
    val host: String,
    val port: Int,
)

internal fun ProxyProfile.endpoint(): MemberEndpoint? =
    when (this) {
        is ProxyProfile.Vless -> MemberEndpoint(server, serverPort)
        is ProxyProfile.VlessReality -> MemberEndpoint(server, serverPort)
        is ProxyProfile.Shadowsocks -> MemberEndpoint(server, serverPort)
        is ProxyProfile.Trojan -> MemberEndpoint(server, serverPort)
        is ProxyProfile.Hysteria2 -> MemberEndpoint(server, serverPort)
        is ProxyProfile.AnyTls -> MemberEndpoint(server, serverPort)
        is ProxyProfile.Mieru -> MemberEndpoint(server, serverPort)
        is ProxyProfile.Ssh -> MemberEndpoint(server, serverPort)
        is ProxyProfile.RawConfig -> null
    }
