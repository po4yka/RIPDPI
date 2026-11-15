package com.poyka.ripdpi.assets

internal object GeoAssetTestFixtures {
    fun country(): ByteArray = read("GeoIP2-Country-Test.mmdb")

    fun asn(): ByteArray = read("GeoLite2-ASN-Test.mmdb")

    // One v0 category with one exact-domain rule. Header and string lengths are uvarints.
    fun geosite(domain: String = "exact.example"): ByteArray =
        byteArrayOf(0, 1, 4) + "test".toByteArray() + byteArrayOf(0, 1, 0, domain.length.toByte()) +
            domain.toByteArray()

    private fun read(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/geo/$name")).use { it.readBytes() }
}
