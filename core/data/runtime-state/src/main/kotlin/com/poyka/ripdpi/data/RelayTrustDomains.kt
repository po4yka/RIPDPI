package com.poyka.ripdpi.data

import java.util.Locale

data class RelayTrustDomain(
    val jurisdiction: String = "",
    val operatorName: String = "",
)

data class RelayTrustDomainWarning(
    val sharedJurisdiction: String? = null,
    val sharedOperatorName: String? = null,
)

fun RelayProfileRecord.toRelayTrustDomain(): RelayTrustDomain =
    RelayTrustDomain(
        jurisdiction = jurisdiction,
        operatorName = operatorName,
    )

fun detectRelayChainTrustWarning(
    entry: RelayTrustDomain,
    exit: RelayTrustDomain,
): RelayTrustDomainWarning? {
    val sharedJurisdiction =
        sharedTrustDomainValue(entry.jurisdiction, exit.jurisdiction)
    val sharedOperator =
        sharedTrustDomainValue(entry.operatorName, exit.operatorName)
    if (sharedJurisdiction == null && sharedOperator == null) {
        return null
    }
    return RelayTrustDomainWarning(
        sharedJurisdiction = sharedJurisdiction,
        sharedOperatorName = sharedOperator,
    )
}

private fun sharedTrustDomainValue(
    entryValue: String,
    exitValue: String,
): String? {
    val normalizedEntry = entryValue.normalizedTrustDomain()
    if (normalizedEntry.isEmpty() || normalizedEntry != exitValue.normalizedTrustDomain()) {
        return null
    }
    return entryValue.trim()
}

private fun String.normalizedTrustDomain(): String = trim().lowercase(Locale.ROOT)
