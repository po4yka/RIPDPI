package com.poyka.ripdpi.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminologyCanonTest {
    @Test
    fun `app string resources do not expose retired M1 terminology`() {
        val resourcesDir = File("src/main/res")
        val stringFiles =
            resourcesDir
                .walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.name == "strings.xml" &&
                        file.parentFile?.name?.startsWith("values") == true
                }.toList()

        // 9 locales: en (values) + ru, es, de, fr, fa, ar, zh-rCN, hi (Hindi added in 9c76d550a).
        assertEquals(9, stringFiles.size)

        val retiredTerms =
            listOf(
                "Desync method",
                "Strategy chain",
                "on-device packet strategies",
                "packet strategy controls",
                "Active packet transformation strategy",
                "CLI overrides chain",
                "VPN with Remote Server",
                "Local DPI Bypass",
                "LOCAL DPI BYPASS",
                "Configuration area",
            )

        val offenders =
            stringFiles.flatMap { file ->
                stringValueRegex
                    .findAll(file.readText())
                    .mapNotNull { match ->
                        val value = match.groups[2]?.value.orEmpty()
                        val term = retiredTerms.firstOrNull { term -> value.contains(term, ignoreCase = true) }
                        if (term == null) {
                            null
                        } else {
                            val name = match.groups[1]?.value.orEmpty()
                            "${file.relativeTo(resourcesDir)}:$name:$term"
                        }
                    }.toList()
            }

        assertTrue("Retired terminology still appears in string resources: $offenders", offenders.isEmpty())
    }

    private companion object {
        val stringValueRegex =
            Regex(
                """<string\s+name="([^"]+)"(?:\s+[^>]*)?>(.*?)</string>""",
                RegexOption.DOT_MATCHES_ALL,
            )
    }
}
