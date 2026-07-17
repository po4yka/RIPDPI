package com.poyka.ripdpi.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminologyCanonTest {
    @Test
    fun `guided persona and quick connect distribution use distinct user vocabulary`() {
        val resourcesDir = File("src/main/res")
        val simpleResourcesDir = File("src/simple/res")
        val expectedGuidedLabels =
            mapOf(
                "values" to "Guided",
                "values-ru" to "С подсказками",
                "values-es" to "Guiado",
                "values-de" to "Geführt",
                "values-fr" to "Guidé",
                "values-fa" to "راهنمایی‌شده",
                "values-ar" to "موجّه",
                "values-zh-rCN" to "引导",
                "values-hi" to "निर्देशित",
            )
        val expectedDistributionTitles =
            mapOf(
                "values" to "RIPDPI Quick Connect",
                "values-ru" to "RIPDPI Быстрое подключение",
                "values-es" to "RIPDPI Conexión rápida",
                "values-de" to "RIPDPI Schnellverbindung",
                "values-fr" to "RIPDPI Connexion rapide",
                "values-fa" to "RIPDPI اتصال سریع",
                "values-ar" to "RIPDPI اتصال سريع",
                "values-zh-rCN" to "RIPDPI 快速连接",
                "values-hi" to "RIPDPI त्वरित कनेक्ट",
            )

        expectedGuidedLabels.forEach { (directory, expected) ->
            val values = stringValues(File(resourcesDir, "$directory/strings.xml"))
            assertEquals("$directory persona label", expected, values.getValue("persona_simple"))
        }
        expectedDistributionTitles.forEach { (directory, expected) ->
            val values = stringValues(File(simpleResourcesDir, "$directory/strings.xml"))
            assertEquals("$directory distribution title", expected, values.getValue("simple_title"))
        }

        val simpleEnglishStrings = File(simpleResourcesDir, "values/strings.xml")
        val simpleEnglishValues = stringValues(simpleEnglishStrings)
        assertEquals("RIPDPI Quick Connect", simpleEnglishValues.getValue("app_name"))
        assertTrue(
            "Quick Connect application label must stay locale-independent",
            simpleEnglishStrings.readText().contains(
                "<string name=\"app_name\" translatable=\"false\">RIPDPI Quick Connect</string>",
            ),
        )

        val englishValues = stringValues(File(resourcesDir, "values/strings.xml"))
        assertEquals("Interface mode", englishValues.getValue("settings_persona_title"))
        assertEquals(
            "You can switch between Guided and Advanced later in Settings.",
            englishValues.getValue("onboarding_persona_body"),
        )
    }

    @Test
    fun `home local bypass label stays localized in every translated resource set`() {
        val resourcesDir = File("src/main/res")
        val englishStrings = stringValues(File(resourcesDir, "values/strings.xml"))
        val englishHomeLabel = englishStrings.getValue("home_mode_local_dpi_bypass")
        val translatedDirectories =
            listOf(
                "values-ru",
                "values-es",
                "values-de",
                "values-fr",
                "values-fa",
                "values-ar",
                "values-zh-rCN",
                "values-hi",
            )

        val offenders =
            translatedDirectories.mapNotNull { directory ->
                val values = stringValues(File(resourcesDir, "$directory/strings.xml"))
                val homeLabel = values.getValue("home_mode_local_dpi_bypass")
                val configLabel = values.getValue("config_local_bypass_simple_title")
                if (homeLabel == configLabel && homeLabel != englishHomeLabel) {
                    null
                } else {
                    "$directory:home=$homeLabel:config=$configLabel"
                }
            }

        assertEquals("Local bypass labels leaked English or drifted: $offenders", emptyList<String>(), offenders)
    }

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

        fun stringValues(file: File): Map<String, String> =
            stringValueRegex
                .findAll(file.readText())
                .associate { match ->
                    match.groups[1]!!.value to match.groups[2]!!.value
                }
    }
}
