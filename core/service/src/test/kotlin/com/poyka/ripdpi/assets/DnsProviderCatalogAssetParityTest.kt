package com.poyka.ripdpi.assets

import android.app.Application
import com.poyka.ripdpi.data.BundledDnsProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Guards the two copies of the bundled DNS provider catalog: the runtime asset
 * `core/service/src/main/assets/dns/dns-providers.json` (read by [DnsProviderCatalogLoader]) and the
 * embedded [BundledDnsProviderCatalog] constant in `:core:data:model` (the offline source the lower
 * data layers and their pure-JVM tests use). They MUST stay structurally identical so a provider
 * edit in one place is mirrored in the other.
 */
@RunWith(RobolectricTestRunner::class)
class DnsProviderCatalogAssetParityTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `bundled asset matches embedded catalog constant`() {
        val fromAsset = DnsProviderCatalogLoader(application).loadCatalog()
        val embedded = BundledDnsProviderCatalog

        assertEquals(embedded.schema, fromAsset.schema)
        assertEquals(
            "embedded catalog and bundled asset advertise different provider ids",
            embedded.providers.map { it.id },
            fromAsset.providers.map { it.id },
        )
        assertEquals(
            "embedded catalog and bundled asset diverged on a provider entry",
            embedded.providers,
            fromAsset.providers,
        )
    }
}
