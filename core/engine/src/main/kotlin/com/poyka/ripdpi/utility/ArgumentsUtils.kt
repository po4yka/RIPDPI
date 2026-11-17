package com.poyka.ripdpi.utility

/**
 * Splits a command line into arguments. Whitespace separates arguments, single and double quotes group text,
 * and a backslash outside single quotes takes the next character literally. An empty quoted string ('' or "")
 * is kept as an empty argument.
 */
fun shellSplit(input: CharSequence): List<String> {
    val args = mutableListOf<String>()
    val arg = StringBuilder()
    var started = false
    var quote: Char? = null
    var escaped = false
    for (c in input) {
        when {
            escaped -> {
                arg.append(c)
                escaped = false
                started = true
            }

            c == '\\' && quote != '\'' -> {
                escaped = true
            }

            quote != null -> {
                if (c == quote) quote = null else arg.append(c)
            }

            c == '\'' || c == '"' -> {
                quote = c
                started = true
            }

            Character.isWhitespace(c) -> {
                if (started) {
                    args += arg.toString()
                    arg.clear()
                    started = false
                }
            }

            else -> {
                arg.append(c)
                started = true
            }
        }
    }
    if (started) args += arg.toString()
    return args
}
