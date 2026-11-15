package com.poyka.ripdpi.core.detection.privacy

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.detectionScope

internal fun DetectionCheckResult.redactAppIdentities(privacyModeEnabled: Boolean): DetectionCheckResult =
    if (privacyModeEnabled) {
        copy(
            directSigns = directSigns.redactAppIdentities(),
            indirectSigns = indirectSigns.redactAppIdentities(),
            // Rebuild narrative rows from the private view. The cached rows can contain app identities.
            verdictNarrative = null,
        )
    } else {
        this
    }

private fun CategoryResult.redactAppIdentities(): CategoryResult =
    copy(
        findings = findings.map(Finding::redactAppIdentity),
        evidence = evidence.map(EvidenceItem::redactAppIdentity),
        matchedApps = emptyList(),
        activeApps = emptyList(),
    )

private fun Finding.redactAppIdentity(): Finding =
    if (packageName != null || source.hasAppIdentity() || source == EvidenceSource.LOCAL_PROXY) {
        copy(description = AppDetailsHidden, family = null, packageName = null)
    } else {
        this
    }

private fun EvidenceItem.redactAppIdentity(): EvidenceItem =
    if (packageName != null || source.hasAppIdentity() || scope == DetectionScope.LOCAL_INVENTORY) {
        copy(description = AppDetailsHidden, family = null, packageName = null)
    } else {
        this
    }

private fun EvidenceSource?.hasAppIdentity(): Boolean =
    this == EvidenceSource.ACTIVE_VPN || this?.detectionScope() == DetectionScope.LOCAL_INVENTORY

private const val AppDetailsHidden = "App identity details hidden"
