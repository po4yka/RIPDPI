package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.NativeSignsResult

internal fun NativeSignsResult.presentedCategory(): CategoryResult {
    val findings = category.findings.filter { it.description.isAppRuntimeFinding() }
    return category.copy(
        findings = findings,
        evidence = category.evidence.filter { it.description.isAppRuntimeFinding() },
        needsReview = findings.any { it.needsReview },
    )
}

private fun String.isAppRuntimeFinding(): Boolean =
    startsWith("Hidden native interface:") ||
        startsWith("Hook library marker in process maps:") ||
        startsWith("Large RWX memory region:") ||
        startsWith("SELinux mode:") ||
        startsWith("Native signs unavailable:")
