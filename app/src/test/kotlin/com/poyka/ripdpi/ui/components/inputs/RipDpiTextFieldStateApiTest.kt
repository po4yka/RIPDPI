package com.poyka.ripdpi.ui.components.inputs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RipDpiTextFieldStateApiTest {
    @Test
    fun `shared text fields expose only the state based editing API`() {
        val source = source().readText().withoutComments()
        val textField = source.functionSource("RipDpiTextField")
        val configTextField = source.functionSource("RipDpiConfigTextField")
        val behaviorParameters = source.primaryConstructorParameters("RipDpiTextFieldBehavior")
        val publicApi = listOf(textField.parameters, configTextField.parameters, behaviorParameters).joinToString("\n")
        val basicTextFieldArguments = textField.body.callArguments("BasicTextField")

        val violations =
            buildList {
                listOf(textField, configTextField).forEach { function ->
                    if (!Regex("""\bstate\s*:\s*TextFieldState\b""").containsMatchIn(function.parameters)) {
                        add("${function.name} does not accept TextFieldState")
                    }
                    if (Regex("""\bvalue\s*:\s*String\b""").containsMatchIn(function.parameters)) {
                        add("${function.name} still exposes value: String")
                    }
                    if (Regex("""\bonValueChange\b""").containsMatchIn(function.parameters)) {
                        add("${function.name} still exposes onValueChange")
                    }
                }

                if (Regex("""\bsingleLine\s*:""").containsMatchIn(behaviorParameters) ||
                    Regex("""\bbehavior\.singleLine\b""").containsMatchIn(textField.body + configTextField.body)
                ) {
                    add("RipDpiTextFieldBehavior still exposes or consumes singleLine")
                }
                if (Regex("""\bVisualTransformation\b""").containsMatchIn(publicApi)) {
                    add("the shared API still exposes VisualTransformation")
                }

                listOf("InputTransformation", "OutputTransformation", "TextFieldLineLimits").forEach { type ->
                    if (!Regex("""\b$type\b""").containsMatchIn(publicApi)) {
                        add("the shared API does not expose $type")
                    }
                }

                if (!Regex("""\bstate\s*=\s*state\b""").containsMatchIn(basicTextFieldArguments)) {
                    add("BasicTextField is not called with state = state")
                }
                if (Regex("""\bvalue\s*=""").containsMatchIn(basicTextFieldArguments) ||
                    Regex("""\bonValueChange\s*=""").containsMatchIn(basicTextFieldArguments)
                ) {
                    add("BasicTextField still uses the value-based overload")
                }
            }

        assertTrue("State-based text-field migration violations: $violations", violations.isEmpty())
    }

    private fun source(): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiTextField.kt"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiTextField.kt"),
        ).first(File::isFile)

    private fun String.withoutComments(): String =
        replace(Regex("""(?s)/\*.*?\*/"""), "")
            .replace(Regex("""(?m)//.*$"""), "")

    private fun String.functionSource(name: String): FunctionSource {
        val start = checkNotNull(Regex("""\bfun\s+$name\s*\(""").find(this)) { "Missing function $name" }.range.first
        val parametersStart = indexOf('(', start)
        val parametersEnd = matchingDelimiter(parametersStart, '(', ')')
        val bodyStart = indexOf('{', parametersEnd)
        val bodyEnd = matchingDelimiter(bodyStart, '{', '}')
        return FunctionSource(
            name = name,
            parameters = substring(parametersStart + 1, parametersEnd),
            body = substring(bodyStart + 1, bodyEnd),
        )
    }

    private fun String.primaryConstructorParameters(className: String): String {
        val start =
            checkNotNull(Regex("""\bdata\s+class\s+$className\s*\(""").find(this)) {
                "Missing data class $className"
            }.range.first
        val parametersStart = indexOf('(', start)
        val parametersEnd = matchingDelimiter(parametersStart, '(', ')')
        return substring(parametersStart + 1, parametersEnd)
    }

    private fun String.callArguments(callName: String): String {
        val callStart =
            checkNotNull(
                Regex("""\b$callName\s*\(""").find(this),
            ) { "Missing call to $callName" }.range.first
        val argumentsStart = indexOf('(', callStart)
        val argumentsEnd = matchingDelimiter(argumentsStart, '(', ')')
        return substring(argumentsStart + 1, argumentsEnd)
    }

    private fun String.matchingDelimiter(
        openingIndex: Int,
        opening: Char,
        closing: Char,
    ): Int {
        var depth = 0
        for (index in openingIndex until length) {
            when (this[index]) {
                opening -> {
                    depth++
                }

                closing -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("Unbalanced $opening$closing delimiters")
    }

    private data class FunctionSource(
        val name: String,
        val parameters: String,
        val body: String,
    )
}
