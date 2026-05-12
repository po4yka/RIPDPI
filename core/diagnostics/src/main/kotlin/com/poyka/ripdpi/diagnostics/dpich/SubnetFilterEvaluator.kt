package com.poyka.ripdpi.diagnostics.dpich

data class IpRange(
    val cidr: String,
)

data class SubnetMetadata(
    val range: IpRange,
    val asn: Int,
    val org: String,
    val country: String,
)

interface SubnetMetadataLookup {
    fun subnetsForCountry(countryCode: String): Set<IpRange>

    fun countryForIp(ip: String): String? = null

    fun subnetsForOrgTerm(term: String): Set<IpRange>

    fun orgTermsForAsn(asn: Int): Set<String>

    fun orgTermsForIp(ip: String): Set<String>

    fun subnetsForAsn(asn: Int): Set<IpRange>

    fun asnForIp(ip: String): Int?

    fun subnetForIp(ip: String): IpRange?
}

interface SubnetFilterDnsResolver {
    suspend fun resolve(hostname: String): Set<String>
}

object EmptyDnsResolver : SubnetFilterDnsResolver {
    override suspend fun resolve(hostname: String): Set<String> = emptySet()
}

class SubnetFilterEvaluator(
    private val geoipDb: SubnetMetadataLookup,
    private val dnsResolver: SubnetFilterDnsResolver = EmptyDnsResolver,
) {
    private val cache = mutableMapOf<SubnetFilterAst, Set<IpRange>>()

    suspend fun evaluate(ast: SubnetFilterAst): Set<IpRange> {
        cache[ast]?.let { return it }
        val result =
            when (ast) {
                SubnetFilterAst.Empty -> emptySet()
                is SubnetFilterAst.Org -> evaluateOrg(ast.args)
                is SubnetFilterAst.As -> evaluateAs(ast.args)
                is SubnetFilterAst.Country -> evaluateCountry(ast.args)
                is SubnetFilterAst.Subnet -> evaluateSubnet(ast.args)
                is SubnetFilterAst.Host -> evaluateHost(ast.args)
                is SubnetFilterAst.And -> evaluate(ast.left).intersect(evaluate(ast.right))
                is SubnetFilterAst.Or -> evaluate(ast.left) + evaluate(ast.right)
            }
        cache[ast] = result
        return result
    }

    private fun evaluateOrg(args: List<String>): Set<IpRange> =
        args.flatMapTo(mutableSetOf()) { arg ->
            when (inferArgType(arg)) {
                ArgType.Asn -> {
                    val asn = arg.toIntOrNull() ?: return@flatMapTo emptySet()
                    geoipDb.orgTermsForAsn(asn).flatMap { geoipDb.subnetsForOrgTerm(it) }
                }

                ArgType.Ip -> {
                    geoipDb.orgTermsForIp(arg).flatMap { geoipDb.subnetsForOrgTerm(it) }
                }

                ArgType.Cidr,
                ArgType.Term,
                -> {
                    geoipDb.subnetsForOrgTerm(arg.trim().lowercase())
                }
            }
        }

    private fun evaluateAs(args: List<String>): Set<IpRange> =
        args.flatMapTo(mutableSetOf()) { arg ->
            when (inferArgType(arg)) {
                ArgType.Asn -> {
                    val asn = arg.toIntOrNull() ?: return@flatMapTo emptySet()
                    geoipDb.subnetsForAsn(asn)
                }

                ArgType.Ip -> {
                    geoipDb.asnForIp(arg)?.let { geoipDb.subnetsForAsn(it) }.orEmpty()
                }

                ArgType.Cidr,
                ArgType.Term,
                -> {
                    emptySet()
                }
            }
        }

    private fun evaluateCountry(args: List<String>): Set<IpRange> =
        args.flatMapTo(mutableSetOf()) { geoipDb.subnetsForCountry(it.lowercase()) }

    private fun evaluateSubnet(args: List<String>): Set<IpRange> =
        args.flatMapTo(mutableSetOf()) { arg ->
            when (inferArgType(arg)) {
                ArgType.Cidr -> setOf(IpRange(arg))

                ArgType.Ip -> geoipDb.subnetForIp(arg)?.let(::setOf).orEmpty()

                ArgType.Asn,
                ArgType.Term,
                -> emptySet()
            }
        }

    private suspend fun evaluateHost(args: List<String>): Set<IpRange> {
        val result = mutableSetOf<IpRange>()
        for (hostname in args) {
            dnsResolver.resolve(hostname).mapNotNullTo(result) { geoipDb.subnetForIp(it) }
        }
        return result
    }
}

fun SubnetMetadataLookup.matchesFilterForIp(
    ip: String,
    ast: SubnetFilterAst,
): Boolean =
    when (ast) {
        SubnetFilterAst.Empty -> {
            false
        }

        is SubnetFilterAst.Org -> {
            ast.args.any { arg -> ipMatchesOrg(arg, ip) }
        }

        is SubnetFilterAst.As -> {
            ast.args.any { arg -> ipMatchesAs(arg, ip) }
        }

        is SubnetFilterAst.Country -> {
            ast.args.any { country ->
                country.equals(countryForIp(ip), ignoreCase = true)
            }
        }

        is SubnetFilterAst.Subnet -> {
            ast.args.any { arg -> ipMatchesSubnet(arg, ip) }
        }

        is SubnetFilterAst.Host -> {
            false
        }

        is SubnetFilterAst.And -> {
            matchesFilterForIp(ip, ast.left) && matchesFilterForIp(ip, ast.right)
        }

        is SubnetFilterAst.Or -> {
            matchesFilterForIp(ip, ast.left) || matchesFilterForIp(ip, ast.right)
        }
    }

private fun SubnetMetadataLookup.ipMatchesOrg(
    arg: String,
    ip: String,
): Boolean {
    val orgTerms = orgTermsForIp(ip).mapTo(linkedSetOf()) { term -> term.lowercase() }
    return when (inferArgType(arg)) {
        ArgType.Asn -> {
            arg.toIntOrNull()?.let { asn ->
                orgTerms.any { term ->
                    term in orgTermsForAsn(asn).lowercaseSet()
                }
            } == true
        }

        ArgType.Ip -> {
            orgTerms.any { term ->
                term in orgTermsForIp(arg).lowercaseSet()
            }
        }

        ArgType.Cidr,
        ArgType.Term,
        -> {
            orgTerms.any { term -> term.contains(arg.trim().lowercase()) }
        }
    }
}

private fun SubnetMetadataLookup.ipMatchesAs(
    arg: String,
    ip: String,
): Boolean =
    when (inferArgType(arg)) {
        ArgType.Asn -> {
            arg.toIntOrNull()?.let { asn -> asnForIp(ip) == asn } == true
        }

        ArgType.Ip -> {
            val expectedAsn = asnForIp(arg)
            expectedAsn != null && asnForIp(ip) == expectedAsn
        }

        ArgType.Cidr,
        ArgType.Term,
        -> {
            false
        }
    }

private fun SubnetMetadataLookup.ipMatchesSubnet(
    arg: String,
    ip: String,
): Boolean =
    when (inferArgType(arg)) {
        ArgType.Cidr -> {
            IpRange(arg).contains(ip)
        }

        ArgType.Ip -> {
            subnetForIp(ip) == subnetForIp(arg)
        }

        ArgType.Asn,
        ArgType.Term,
        -> {
            false
        }
    }

private fun Set<String>.lowercaseSet(): Set<String> = mapTo(linkedSetOf()) { value -> value.lowercase() }

enum class ArgType {
    Asn,
    Cidr,
    Ip,
    Term,
}

fun inferArgType(value: String): ArgType =
    when {
        value.all(Char::isDigit) -> ArgType.Asn
        isIpv4Cidr(value) -> ArgType.Cidr
        isIpv4Address(value) -> ArgType.Ip
        else -> ArgType.Term
    }

private fun isIpv4Cidr(value: String): Boolean {
    val parts = value.split("/")
    if (parts.size != 2) return false
    val prefix = parts[1].toIntOrNull() ?: return false
    return prefix in 0..32 && isIpv4Address(parts[0])
}

private fun isIpv4Address(value: String): Boolean {
    val octets = value.split(".")
    return octets.size == 4 &&
        octets.all { octet ->
            octet.isNotEmpty() &&
                octet.all(Char::isDigit) &&
                octet.toIntOrNull()?.let { it in 0..255 } == true
        }
}

fun IpRange.contains(ip: String): Boolean {
    val (network, prefixText) = cidr.split('/').takeIf { parts -> parts.size == 2 } ?: return false
    val prefix = prefixText.toIntOrNull()?.takeIf { value -> value in Ipv4PrefixRange } ?: return false
    val networkBits = network.toIpv4Bits() ?: return false
    val ipBits = ip.toIpv4Bits() ?: return false
    val mask = if (prefix == 0) 0 else -1 shl (Ipv4BitCount - prefix)
    return networkBits and mask == ipBits and mask
}

private fun String.toIpv4Bits(): Int? {
    val octets = split('.')
    if (octets.size != Ipv4OctetCount) return null
    return octets.fold(0) { acc, octet ->
        val value = octet.toIntOrNull()?.takeIf { parsed -> parsed in Ipv4OctetRange } ?: return null
        (acc shl Byte.SIZE_BITS) or value
    }
}

private const val Ipv4BitCount = 32
private const val Ipv4OctetCount = 4
private val Ipv4PrefixRange = 0..32
private val Ipv4OctetRange = 0..255
