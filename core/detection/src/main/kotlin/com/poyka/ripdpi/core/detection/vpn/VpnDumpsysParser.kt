package com.poyka.ripdpi.core.detection.vpn

data class ParsedActiveVpnRecord(
    val packageName: String?,
    val serviceName: String?,
    val rawLine: String,
)

object VpnDumpsysParser {
    private val activePackageRegex = Regex("Active package name:\\s*(\\S+)")
    private val serviceRecordRegex = Regex("\\{[^}]*\\s+(\\S+?)/(\\S+)\\}")

    fun isUnavailable(output: String): Boolean =
        output.isBlank() ||
            output.contains("Permission Denial") ||
            output.contains("Can't find service")

    fun parseVpnManagement(output: String): List<ParsedActiveVpnRecord> {
        if (isUnavailable(output)) return emptyList()

        val records = mutableListOf<ParsedActiveVpnRecord>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            val packageName = activePackageRegex.find(trimmed)?.groupValues?.getOrNull(1)
            if (packageName != null) {
                records.add(
                    ParsedActiveVpnRecord(
                        packageName = packageName,
                        serviceName = null,
                        rawLine = trimmed,
                    ),
                )
            } else if (trimmed.matches(Regex("^\\d+:.*"))) {
                records.add(
                    ParsedActiveVpnRecord(
                        packageName = null,
                        serviceName = null,
                        rawLine = trimmed,
                    ),
                )
            }
        }

        return records.distinctBy { Triple(it.packageName, it.serviceName, it.rawLine) }
    }

    fun parseVpnServices(output: String): List<ParsedActiveVpnRecord> {
        if (isUnavailable(output)) return emptyList()

        return output
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains("ServiceRecord") && it.contains("VpnService") }
            .map { line ->
                val match = serviceRecordRegex.find(line)
                val packageName = match?.groupValues?.getOrNull(1)
                val serviceName = match?.groupValues?.getOrNull(2)
                ParsedActiveVpnRecord(
                    packageName = packageName,
                    serviceName = serviceName,
                    rawLine = line,
                )
            }.distinctBy { Triple(it.packageName, it.serviceName, it.rawLine) }
            .toList()
    }
}
