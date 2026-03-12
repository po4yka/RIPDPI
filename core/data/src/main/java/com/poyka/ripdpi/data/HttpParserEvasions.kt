package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val HttpParserEvasionHostMixedCase = "host_mixed_case"
const val HttpParserEvasionDomainMixedCase = "domain_mixed_case"
const val HttpParserEvasionHostRemoveSpaces = "host_remove_spaces"
const val HttpParserEvasionMethodEol = "method_eol"
const val HttpParserEvasionUnixEol = "unix_eol"

fun activeHttpParserEvasions(
    hostMixedCase: Boolean,
    domainMixedCase: Boolean,
    hostRemoveSpaces: Boolean,
    httpMethodEol: Boolean,
    httpUnixEol: Boolean,
): List<String> =
    buildList {
        if (hostMixedCase) add(HttpParserEvasionHostMixedCase)
        if (domainMixedCase) add(HttpParserEvasionDomainMixedCase)
        if (hostRemoveSpaces) add(HttpParserEvasionHostRemoveSpaces)
        if (httpUnixEol) add(HttpParserEvasionUnixEol)
        if (httpMethodEol) add(HttpParserEvasionMethodEol)
    }

fun AppSettings.activeHttpParserEvasions(): List<String> =
    activeHttpParserEvasions(
        hostMixedCase = hostMixedCase,
        domainMixedCase = domainMixedCase,
        hostRemoveSpaces = hostRemoveSpaces,
        httpMethodEol = httpMethodEol,
        httpUnixEol = httpUnixEol,
    )
