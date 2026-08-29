package com.poyka.ripdpi.diagnostics.ech

/**
 * Generates a `<network-security-config>` XML document with per-domain
 * `<domainEncryption>` entries for Android 17 (API 37).
 */
object NscDomainEncryptionGenerator {
    /**
     * Produces a well-formed NSC XML string from a map of domain → [EchMode].
     * Each domain gets its own `<domain-config>` block with a `<domainEncryption>`
     * child whose `mode` attribute matches the requested [EchMode].
     */
    fun generate(domains: Map<String, EchMode>): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.appendLine("<network-security-config>")
        sb.appendLine("    <base-config cleartextTrafficPermitted=\"false\" />")
        for ((domain, mode) in domains) {
            sb.appendLine("    <domain-config cleartextTrafficPermitted=\"false\">")
            sb.appendLine("        <domain includeSubdomains=\"false\">$domain</domain>")
            sb.appendLine("        <domainEncryption mode=\"${mode.xmlValue()}\" />")
            sb.appendLine("    </domain-config>")
        }
        sb.append("</network-security-config>")
        return sb.toString()
    }

    /**
     * Classifies a list of domains into an [EchMode] map using a simple
     * owned-stack heuristic: domains in [ownedStackDomains] get [EchMode.Enabled];
     * everything else gets [EchMode.Opportunistic].
     */
    fun classifyDomains(
        domains: List<String>,
        ownedStackDomains: Set<String>,
    ): Map<String, EchMode> =
        domains.associateWith { domain ->
            if (domain in ownedStackDomains) EchMode.Enabled else EchMode.Opportunistic
        }

    /**
     * Merges a strategy-pack [override] on top of a [base] domain→mode map.
     * Override entries take precedence; any domain absent from the override
     * retains its base value.
     */
    fun applyOverride(
        base: Map<String, EchMode>,
        override: NscStrategyPackOverride,
    ): Map<String, EchMode> = base + override.domainModes

    private fun EchMode.xmlValue(): String =
        when (this) {
            EchMode.Enabled -> "enabled"
            EchMode.Disabled -> "disabled"
            EchMode.Opportunistic -> "enabled"
        }
}
