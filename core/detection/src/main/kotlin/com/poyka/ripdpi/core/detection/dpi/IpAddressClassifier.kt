@file:Suppress("MagicNumber", "ReturnCount")

package com.poyka.ripdpi.core.detection.dpi

import com.poyka.ripdpi.data.NumericIp
import java.net.InetAddress

enum class IpAddressType {
    FAKE_IP,
    ISP_STUB,
    LOCAL,
    PUBLIC,
}

object IpAddressClassifier {
    fun classify(ip: String): IpAddressType {
        val parsed = NumericIp.parseAddress(ip.trim()) ?: return IpAddressType.LOCAL
        val address = if (parsed.isIPv6 && parsed.toIPv6().isIPv4Mapped) parsed.toIPv4() else parsed
        return when {
            FakeIpRange.contains(address) -> IpAddressType.FAKE_IP
            IspStubRange.contains(address) -> IpAddressType.ISP_STUB
            address.toInetAddress().isLocalAddress() -> IpAddressType.LOCAL
            else -> IpAddressType.PUBLIC
        }
    }
}

private fun InetAddress.isLocalAddress(): Boolean =
    isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress

private val FakeIpRange = requireNotNull(NumericIp.parseCidr("198.18.0.0/15"))
private val IspStubRange = requireNotNull(NumericIp.parseCidr("100.64.0.0/10"))
