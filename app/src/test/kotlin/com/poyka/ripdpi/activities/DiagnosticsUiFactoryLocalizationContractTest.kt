package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DiagnosticsUiFactoryLocalizationContractTest {
    @Test
    fun `audited ui factories contain no English display literals`() {
        val sources =
            listOf(
                "HistoryConnectionDetailUiFactory.kt",
                "DiagnosticsSessionDetailUiFactory.kt",
                "DiagnosticsUiCoreSupport.kt",
            ).map(::activitySource)
        val displayLiteral = Regex("\\\"[A-Z][A-Za-z][A-Za-z ]+\\\"")

        val literals =
            sources
                .flatMap { source ->
                    displayLiteral.findAll(source.readText()).map { match -> match.value }.toList()
                }.filterNot { it == "\"LongMethod\"" }

        assertEquals(emptyList<String>(), literals)
    }

    private fun activitySource(name: String): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/activities/$name"),
            File("app/src/main/kotlin/com/poyka/ripdpi/activities/$name"),
        ).first(File::isFile)
}
