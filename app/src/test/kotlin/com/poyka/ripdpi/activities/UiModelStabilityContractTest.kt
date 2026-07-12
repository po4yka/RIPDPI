package com.poyka.ripdpi.activities

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiModelStabilityContractTest {
    @Test
    fun `annotated ui models expose only immutable lists`() {
        val violations =
            sourceRoot()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap(::plainListViolations)
                .toList()

        assertTrue(
            "@Stable and @Immutable models must use ImmutableList:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun plainListViolations(file: File): Sequence<String> =
        sequence {
            val source = file.readText()
            AnnotatedClass.findAll(source).forEach { match ->
                val className = match.groupValues[2]
                val constructorStart = source.indexOf('(', match.range.last + 1)
                if (constructorStart < 0) return@forEach
                val constructorEnd = source.findClosingDelimiter(constructorStart, '(', ')')
                if (constructorEnd < 0) return@forEach
                val constructor = source.substring(constructorStart, constructorEnd + 1)
                PlainList.findAll(constructor).forEach { listMatch ->
                    val offset = constructorStart + listMatch.range.first
                    yield("${file.relativeTo(sourceRoot()).path}:${source.lineNumber(offset)} $className")
                }

                val bodyStart = source.indexOfFirstNonWhitespace(constructorEnd + 1)
                if (bodyStart < 0 || source[bodyStart] != '{') return@forEach
                val bodyEnd = source.findClosingDelimiter(bodyStart, '{', '}')
                if (bodyEnd < 0) return@forEach
                val body = source.substring(bodyStart, bodyEnd + 1)
                PlainBodyList.findAll(body).forEach { listMatch ->
                    val offset = bodyStart + listMatch.range.first
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
        val AnnotatedClass = Regex("""@(Stable|Immutable)\s+(?:data\s+)?class\s+(\w+)""")
        val PlainList = Regex("""(?<!Immutable)\bList\s*<""")
        val PlainBodyList = Regex("""(?m)^ {4}(?:override\s+)?val\s+\w+\s*:\s*(?<!Immutable)List\s*<""")
    }
}
