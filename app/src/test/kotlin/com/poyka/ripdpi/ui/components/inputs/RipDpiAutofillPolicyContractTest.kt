package com.poyka.ripdpi.ui.components.inputs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RipDpiAutofillPolicyContractTest {
    @Test
    fun `only login credentials opt in to autofill`() {
        val screens = sourceRoot().walkTopDown().filter { it.extension == "kt" }.toList()
        val expectedPolicies =
            mapOf(
                "config/RelayNaiveProxyFields.kt" to listOf("Username", "Password"),
                "anytls/AnyTlsProfileScreen.kt" to listOf("Password"),
                "mieru/MieruProfileScreen.kt" to listOf("Username", "Password"),
                "ssh/SshProfileScreen.kt" to listOf("Username", "Password"),
                "config/RelayHysteria2Fields.kt" to listOf("Password"),
                "config/RelayShadowTlsFields.kt" to listOf("Password"),
                "config/RelayTuicFields.kt" to listOf("Password"),
            )
        val actualPolicies =
            buildMap<String, MutableList<String>> {
                screens.forEach { file ->
                    Regex("""autofillPolicy\s*=\s*RipDpiAutofillPolicy\.(Username|Password)""")
                        .findAll(file.readText())
                        .forEach { match ->
                            getOrPut(file.relativeTo(sourceRoot()).invariantSeparatorsPath) { mutableListOf() }
                                .add(match.groupValues[1])
                        }
                }
            }

        assertTrue(
            "Credential autofill policies differ: expected=$expectedPolicies actual=$actualPolicies",
            actualPolicies == expectedPolicies,
        )
    }

    private fun sourceRoot(): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/screens"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/screens"),
        ).first(File::isDirectory)
}
