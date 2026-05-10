package com.poyka.ripdpi.diagnostics.dpich

class SubnetFilterParseError(
    val position: Int,
    detail: String,
) : IllegalArgumentException("$detail at position $position")

object SubnetFilterDsl {
    fun parse(expr: String): SubnetFilterAst {
        if (expr.isBlank()) return SubnetFilterAst.Empty
        return Parser(expr).parse()
    }
}

private class Parser(
    private val input: String,
) {
    private var position = 0

    fun parse(): SubnetFilterAst {
        val ast = parseOr()
        skipWhitespace()
        if (!isAtEnd()) {
            error("Unexpected token")
        }
        return ast
    }

    private fun parseOr(): SubnetFilterAst {
        var left = parseAnd()
        while (true) {
            skipWhitespace()
            if (!match("||")) return left
            left = SubnetFilterAst.Or(left = left, right = parseAnd())
        }
    }

    private fun parseAnd(): SubnetFilterAst {
        var left = parsePrimary()
        while (true) {
            skipWhitespace()
            if (!match("&&")) return left
            left = SubnetFilterAst.And(left = left, right = parsePrimary())
        }
    }

    private fun parsePrimary(): SubnetFilterAst {
        skipWhitespace()
        if (match("(")) {
            val grouped = parseOr()
            expect(")", "Expected ')'")
            return grouped
        }
        return parseCall()
    }

    private fun parseCall(): SubnetFilterAst {
        val name = parseIdentifier()
        skipWhitespace()
        expect("(", "Expected '(' after function name")
        val args = parseArguments()
        expect(")", "Expected ')'")
        return when (name) {
            "org" -> SubnetFilterAst.Org(args)
            "as" -> SubnetFilterAst.As(args)
            "country" -> SubnetFilterAst.Country(args)
            "subnet" -> SubnetFilterAst.Subnet(args)
            "host" -> SubnetFilterAst.Host(args)
            else -> error("Unknown function '$name'")
        }
    }

    private fun parseArguments(): List<String> {
        skipWhitespace()
        if (peek() == ')') return emptyList()

        val args = mutableListOf<String>()
        while (true) {
            args += parseArgument()
            skipWhitespace()
            if (peek() != ',') return args
            position++
            skipWhitespace()
            if (peek() == ')') {
                error("Expected argument")
            }
        }
    }

    private fun parseArgument(): String {
        skipWhitespace()
        return when {
            peek() == '"' -> parseString()
            peek()?.isDigit() == true -> parseNumber()
            else -> error("Expected argument")
        }
    }

    private fun parseIdentifier(): String {
        skipWhitespace()
        val start = position
        while (!isAtEnd() && input[position].isLetter()) {
            position++
        }
        if (position == start) {
            error("Expected function name")
        }
        return input.substring(start, position)
    }

    private fun parseNumber(): String {
        val start = position
        while (!isAtEnd() && input[position].isDigit()) {
            position++
        }
        return input.substring(start, position)
    }

    private fun parseString(): String {
        expect("\"", "Expected string")
        val builder = StringBuilder()
        while (!isAtEnd()) {
            val char = input[position++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscapedChar())
                else -> builder.append(char)
            }
        }
        error("Unclosed string")
    }

    private fun parseEscapedChar(): Char {
        if (isAtEnd()) {
            error("Unclosed escape sequence")
        }
        return when (val escaped = input[position++]) {
            '"', '\\', '/' -> escaped
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> error("Unsupported escape sequence")
        }
    }

    private fun match(token: String): Boolean {
        if (!input.startsWith(token, startIndex = position)) return false
        position += token.length
        return true
    }

    private fun expect(
        token: String,
        message: String,
    ) {
        if (!match(token)) {
            error(message)
        }
    }

    private fun skipWhitespace() {
        while (!isAtEnd() && input[position].isWhitespace()) {
            position++
        }
    }

    private fun peek(): Char? = input.getOrNull(position)

    private fun isAtEnd(): Boolean = position >= input.length

    private fun error(message: String): Nothing = throw SubnetFilterParseError(position, message)
}
