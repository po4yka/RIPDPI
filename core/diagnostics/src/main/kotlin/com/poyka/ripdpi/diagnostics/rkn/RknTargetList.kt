package com.poyka.ripdpi.diagnostics.rkn

import co.touchlab.kermit.Logger
import java.net.URI
import java.util.Locale

data class RknTarget(
    val name: String,
    val url: String,
    val host: String,
)

object RknTargetListParser {
    fun parse(payload: String): List<RknTarget> {
        val targetsByName = linkedMapOf<String, RknTarget>()
        payload
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith(CommentPrefix) }
            .forEach { line ->
                val target = parseLine(line)
                if (targetsByName.containsKey(target.name)) {
                    log.w { "Duplicate RKN target name '${target.name}', keeping last entry" }
                }
                targetsByName[target.name] = target
            }
        return targetsByName.values.toList()
    }

    private fun parseLine(line: String): RknTarget {
        val (name, rawUrl) =
            when {
                NameEqualsUrlPattern.matches(line) -> {
                    val parts = line.split("=", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }

                line.any(Char::isWhitespace) -> {
                    val parts = line.split(WhitespacePattern, limit = 2)
                    parts[0].trim() to parts[1].trim()
                }

                else -> {
                    null to line
                }
            }

        val url = rawUrl.withDefaultScheme()
        val host =
            requireNotNull(URI.create(url).host) {
                "RKN target URL must have a host: $rawUrl"
            }.lowercase(Locale.ROOT)
        return RknTarget(
            name = name?.takeIf { it.isNotBlank() } ?: host.replace('.', '-'),
            url = url,
            host = host,
        )
    }

    private fun String.withDefaultScheme(): String = if (contains("://")) this else "https://$this"

    private val WhitespacePattern = Regex("\\s+")
    private val NameEqualsUrlPattern = Regex("[^=\\s]+\\s*=\\s*.+")
    private val log = Logger.withTag("RknTargetList")
    private const val CommentPrefix = "#"
}
