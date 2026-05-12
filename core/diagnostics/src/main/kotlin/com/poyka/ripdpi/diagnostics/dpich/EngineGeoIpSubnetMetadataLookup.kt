package com.poyka.ripdpi.diagnostics.dpich

import com.poyka.ripdpi.core.GeoDatabasePaths
import com.poyka.ripdpi.core.RipDpiGeoIpMetadata
import com.poyka.ripdpi.core.RipDpiProxyBindings
import java.util.concurrent.ConcurrentHashMap

class EngineGeoIpSubnetMetadataLookup(
    private val bindings: RipDpiProxyBindings,
    private val paths: GeoDatabasePaths,
) : SubnetMetadataLookup {
    private val metadataByIp = ConcurrentHashMap<String, LookupResult>()

    override fun subnetsForCountry(countryCode: String): Set<IpRange> = emptySet()

    override fun countryForIp(ip: String): String? = metadataForIp(ip)?.countryCode

    override fun subnetsForOrgTerm(term: String): Set<IpRange> = emptySet()

    override fun orgTermsForAsn(asn: Int): Set<String> = emptySet()

    override fun orgTermsForIp(ip: String): Set<String> =
        metadataForIp(ip)
            ?.organization
            ?.let(::setOf)
            .orEmpty()

    override fun subnetsForAsn(asn: Int): Set<IpRange> = emptySet()

    override fun asnForIp(ip: String): Int? = metadataForIp(ip)?.asn

    override fun subnetForIp(ip: String): IpRange? = null

    private fun metadataForIp(ip: String): RipDpiGeoIpMetadata? =
        metadataByIp
            .computeIfAbsent(ip) {
                LookupResult(
                    try {
                        bindings.geoIpMetadata(
                            geoipDbPath = paths.geoipDbPath,
                            geositeDbPath = paths.geositeDbPath,
                            ip = ip,
                        )
                    } catch (_: IllegalArgumentException) {
                        null
                    },
                )
            }.metadata

    private data class LookupResult(
        val metadata: RipDpiGeoIpMetadata?,
    )
}
