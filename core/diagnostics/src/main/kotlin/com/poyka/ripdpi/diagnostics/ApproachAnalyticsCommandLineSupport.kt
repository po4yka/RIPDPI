package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.utility.shellSplit

internal fun hasCommandLineRawFakePayload(args: String): Boolean {
    val tokens = shellSplit(args)
    return tokens.withIndex().any { (index, token) ->
        (token == "-l" || token == "--fake-data") && index + 1 < tokens.size
    }
}
