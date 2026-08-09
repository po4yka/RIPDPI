package com.poyka.ripdpi.diagnostics.export

internal fun MutableList<String>.addStrategyEvidenceFiles(prefix: String = "") {
    add("${prefix}execution-plan.json")
    add("${prefix}capabilities.json")
    add("${prefix}attempts.jsonl")
    add("${prefix}emission-receipts.jsonl")
    add("${prefix}protocol-milestones.jsonl")
    add("${prefix}decision-trace.json")
}
