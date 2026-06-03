package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the endpoint contract between the legacy [BuiltInDnsProviders] list and the richer
 * [BundledDnsProviderCatalog]. The two lists may legitimately differ on bootstrap breadth — the
 * legacy entries are intentionally IPv4-only while the catalog adds IPv6 bootstraps — but for any
 * id present in BOTH they must never point at different DoH endpoints or TLS servers. A drift here
 * would mean the resolution path (`dnsProviderById`) silently resolves the same provider id to two
 * different upstreams depending on which list wins.
 */
class DnsProviderCatalogConsistencyTest {
    @Test
    fun `shared provider ids agree on doh and tls endpoints`() {
        val catalogById = BundledDnsProviderCatalog.providers.associateBy { it.id }
        val sharedProviders = BuiltInDnsProviders.filter { catalogById.containsKey(it.providerId) }

        assertTrue(
            "Expected at least one id shared between BuiltInDnsProviders and the bundled catalog",
            sharedProviders.isNotEmpty(),
        )

        sharedProviders.forEach { builtIn ->
            val catalog = catalogById.getValue(builtIn.providerId)

            assertEquals(
                "DoH URL must agree for shared provider '${builtIn.providerId}'",
                builtIn.dohUrl,
                catalog.dohUrl,
            )

            // The catalog TLS endpoint is tls_server_name when set, else dot_host (mirrors
            // DnsProviderCatalogEntry.toDnsProviderDefinition).
            val catalogTlsServerName = catalog.tlsServerName.ifBlank { catalog.dotHost }
            assertEquals(
                "TLS server name must agree for shared provider '${builtIn.providerId}'",
                builtIn.tlsServerName,
                catalogTlsServerName,
            )

            // Bootstrap breadth may differ (IPv4-only legacy vs IPv4+IPv6 catalog), but every legacy
            // bootstrap IP must still appear in the catalog entry — a subset check that catches
            // accidental endpoint drift without forbidding the extra IPv6 entries.
            builtIn.bootstrapIps.forEach { ip ->
                assertTrue(
                    "Legacy bootstrap IP '$ip' for '${builtIn.providerId}' is missing from the catalog entry",
                    catalog.bootstrapIps.contains(ip),
                )
            }
        }
    }
}
