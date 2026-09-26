package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConnectionPolicySignatureBuilderTest {
    @Test
    fun lanTokenChangeChangesPolicySignature() {
        val dns =
            ActiveDnsSettings(
                mode = "plain_udp",
                providerId = "",
                dnsIp = "",
                encryptedDnsProtocol = "",
                encryptedDnsHost = "",
                encryptedDnsPort = 0,
                encryptedDnsTlsServerName = "",
                encryptedDnsBootstrapIps = emptyList(),
                encryptedDnsDohUrl = "",
                encryptedDnsDnscryptProviderName = "",
                encryptedDnsDnscryptPublicKey = "",
            )
        fun signature(token: String) =
            buildConnectionPolicySignature(
                mode = Mode.Proxy,
                proxyPreferences =
                    RipDpiProxyUIPreferences(
                        listen = RipDpiListenConfig(ip = "0.0.0.0", authToken = token),
                    ),
                activeDns = dns,
                resolverFallbackReason = null,
                matchedPolicy = null,
            )

        assertNotEquals(signature("first-token"), signature("second-token"))
    }
}
