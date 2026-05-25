package com.poyka.ripdpi.diagnostics.export

internal class DiagnosticsArchivePrivacyGuard {
    fun sanitize(entries: List<DiagnosticsArchiveEntry>): List<DiagnosticsArchiveEntry> =
        entries.mapIndexed { index, entry ->
            when {
                entry.name.endsWith(".pcap", ignoreCase = true) -> {
                    entry.copy(name = safePcapName(index))
                }

                entry.isTextArtifact() -> {
                    entry.copy(
                        bytes = redactDiagnosticsArchiveText(entry.bytes.decodeToString()).toByteArray(),
                    )
                }

                else -> {
                    entry
                }
            }
        }

    fun requireSafe(entries: List<DiagnosticsArchiveEntry>) {
        val violations =
            entries.flatMap { entry ->
                buildList {
                    if (entry.name.endsWith(".pcap", ignoreCase = true) && !PcapEntryNameRegex.matches(entry.name)) {
                        add("${entry.name}: unsafe PCAP archive path")
                    }
                    if (entry.isTextArtifact()) {
                        addAll(scanText(entry.name, entry.bytes.decodeToString()))
                    }
                }
            }
        check(violations.isEmpty()) {
            "Diagnostics archive privacy guard rejected sensitive artifact content: ${violations.joinToString()}"
        }
    }

    private fun scanText(
        name: String,
        content: String,
    ): List<String> =
        buildList {
            ForbiddenTextPatterns.forEach { pattern ->
                if (pattern.mayMatch(content) && pattern.regex.containsMatchIn(content)) {
                    add("$name: ${pattern.description}")
                }
            }
        }

    private fun DiagnosticsArchiveEntry.isTextArtifact(): Boolean =
        TextArtifactSuffixes.any { suffix -> name.endsWith(suffix, ignoreCase = true) }

    private fun safePcapName(index: Int): String = "pcap/capture-${index.toString().padStart(2, '0')}.pcap"

    private data class ForbiddenPattern(
        val description: String,
        val regex: Regex,
        val needles: Set<String>,
    ) {
        fun mayMatch(content: String): Boolean =
            needles.any { needle ->
                content.contains(needle, ignoreCase = true)
            }
    }

    private companion object {
        private val TextArtifactSuffixes = setOf(".json", ".txt", ".csv")
        private val PcapEntryNameRegex = Regex("pcap/capture-\\d{2}\\.pcap")
        private val ForbiddenTextPatterns =
            listOf(
                ForbiddenPattern(
                    description = "authorization credential",
                    regex =
                        Regex(
                            "(?i)\\b(?:Proxy-Authorization|Authorization):\\s*(?:Basic|Bearer)\\s+(?!redacted\\b)\\S+",
                        ),
                    needles = setOf("authorization:"),
                ),
                ForbiddenPattern(
                    description = "credential-bearing URL",
                    regex =
                        Regex(
                            "[a-z][a-z0-9+.-]*://[^\\s/@:]+:[^\\s/@]+@",
                            RegexOption.IGNORE_CASE,
                        ),
                    needles = setOf("://"),
                ),
                ForbiddenPattern(
                    description = "sensitive query token",
                    regex =
                        Regex(
                            "(?i)\\b(?:token|auth|password|secret|key)=(?!redacted\\b)[^\\s&\",]+",
                        ),
                    needles = setOf("token=", "auth=", "password=", "secret=", "key="),
                ),
                ForbiddenPattern(
                    description = "BSSID",
                    regex = Regex("\\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\\b"),
                    needles = setOf(":"),
                ),
                ForbiddenPattern(
                    description = "endpoint field",
                    regex =
                        Regex(
                            "(?i)\\b(?:host|hostname|target|server|resolverEndpoint|endpoint|addr|address)=" +
                                "(?!<redacted>|redacted\\b|unavailable\\b|unknown\\b)[^\\s,;]+",
                        ),
                    needles =
                        setOf(
                            "host=",
                            "target=",
                            "server=",
                            "resolverEndpoint=",
                            "endpoint=",
                            "addr=",
                            "address=",
                        ),
                ),
                ForbiddenPattern(
                    description = "JSON endpoint field",
                    regex =
                        Regex(
                            "\"(?:address|addr|bssid|dhcpServer|endpoint|gateway|host|hostname|ipAddress|" +
                                "listenerAddress|proxyEndpoint|resolverEndpoint|server|ssid|subnetMask|target|" +
                                "upstreamAddress)\"\\s*:\\s*\"" +
                                "(?!<redacted>|redacted\\b|unavailable\\b|unknown\\b|none\\b)[^\"]+\"",
                            RegexOption.IGNORE_CASE,
                        ),
                    needles =
                        setOf(
                            "\"address\"",
                            "\"addr\"",
                            "\"bssid\"",
                            "\"dhcpServer\"",
                            "\"endpoint\"",
                            "\"gateway\"",
                            "\"host\"",
                            "\"hostname\"",
                            "\"ipAddress\"",
                            "\"listenerAddress\"",
                            "\"proxyEndpoint\"",
                            "\"resolverEndpoint\"",
                            "\"server\"",
                            "\"ssid\"",
                            "\"subnetMask\"",
                            "\"target\"",
                            "\"upstreamAddress\"",
                        ),
                ),
                ForbiddenPattern(
                    description = "JSON target host list",
                    regex =
                        Regex(
                            "\"(?:affectedTargets|domainHosts|quicHosts)\"\\s*:\\s*\\[" +
                                "(?!\\s*\"<redacted>\"\\s*])[^]]*]",
                            RegexOption.IGNORE_CASE,
                        ),
                    needles =
                        setOf(
                            "\"affectedTargets\"",
                            "\"domainHosts\"",
                            "\"quicHosts\"",
                        ),
                ),
            )
    }
}
