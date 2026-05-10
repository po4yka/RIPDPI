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
