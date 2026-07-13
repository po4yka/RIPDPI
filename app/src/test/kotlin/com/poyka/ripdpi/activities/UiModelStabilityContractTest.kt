package com.poyka.ripdpi.activities

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiModelStabilityContractTest {
    @Test
    fun `ui models expose only immutable collections`() {
        val violations =
            sourceRoot()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap(::plainCollectionViolations)
                .toList()

        assertTrue(
            "UI models must use immutable collection interfaces:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun plainCollectionViolations(file: File): Sequence<String> =
        sequence {
            val source = file.readText()
            UiModelClass.findAll(source).forEach { match ->
                val className = match.groupValues[2].ifBlank { match.groupValues[3] }
                val constructorStart = source.indexOf('(', match.range.last + 1)
                if (constructorStart < 0) return@forEach
                val constructorEnd = source.findClosingDelimiter(constructorStart, '(', ')')
                if (constructorEnd < 0) return@forEach
                val constructor = source.substring(constructorStart, constructorEnd + 1)
                PlainCollection.findAll(constructor).forEach { collectionMatch ->
                    val offset = constructorStart + collectionMatch.range.first
                    yield("${file.relativeTo(sourceRoot()).path}:${source.lineNumber(offset)} $className")
                }

                val bodyStart = source.indexOfFirstNonWhitespace(constructorEnd + 1)
                if (bodyStart < 0 || source[bodyStart] != '{') return@forEach
                val bodyEnd = source.findClosingDelimiter(bodyStart, '{', '}')
                if (bodyEnd < 0) return@forEach
                val body = source.substring(bodyStart, bodyEnd + 1)
                PlainBodyCollection.findAll(body).forEach { collectionMatch ->
                    val offset = bodyStart + collectionMatch.range.first
                    yield("${file.relativeTo(sourceRoot()).path}:${source.lineNumber(offset)} $className")
                }
            }
        }

    private fun sourceRoot(): File =
        listOf(File("src/main/kotlin"), File("app/src/main/kotlin"))
            .first { it.isDirectory }

    private fun String.findClosingDelimiter(
        start: Int,
        opening: Char,
        closing: Char,
    ): Int {
        var depth = 0
        for (index in start until length) {
            when (this[index]) {
                opening -> {
                    depth += 1
                }

                closing -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun String.lineNumber(offset: Int): Int = take(offset).count { it == '\n' } + 1

    private fun String.indexOfFirstNonWhitespace(start: Int): Int {
        for (index in start until length) {
            if (!this[index].isWhitespace()) return index
        }
        return -1
    }

    private companion object {
        val UiModelClass =
            Regex(
                """(?:@(Stable|Immutable)\s+(?:data\s+)?class\s+(\w+)|""" +
                    """(?:data\s+)?class\s+(\w+(?:UiState|UiModel)\b))""",
            )
        val PlainCollection = Regex("""\b(?:List|Set|Map|Collection)\s*<""")
        val PlainBodyCollection =
            Regex("""(?m)^ {4}(?:override\s+)?val\s+\w+\s*:\s*(?:List|Set|Map|Collection)\s*<""")
    }
}
