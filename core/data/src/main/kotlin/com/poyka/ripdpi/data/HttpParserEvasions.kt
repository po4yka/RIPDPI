package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val HttpParserEvasionHostMixedCase = "host_mixed_case"
const val HttpParserEvasionDomainMixedCase = "domain_mixed_case"
const val HttpParserEvasionHostRemoveSpaces = "host_remove_spaces"
const val HttpParserEvasionMethodEol = "method_eol"
const val HttpParserEvasionMethodSpace = "method_space"
const val HttpParserEvasionUnixEol = "unix_eol"
const val HttpParserEvasionHostPad = "host_pad"

fun activeHttpParserEvasions(
    hostMixedCase: Boolean,
    domainMixedCase: Boolean,
    hostRemoveSpaces: Boolean,
    httpMethodEol: Boolean,
    httpMethodSpace: Boolean,
    httpUnixEol: Boolean,
    httpHostPad: Boolean,
): List<String> =
    buildList {
        if (hostMixedCase) add(HttpParserEvasionHostMixedCase)
        if (domainMixedCase) add(HttpParserEvasionDomainMixedCase)
        if (hostRemoveSpaces) add(HttpParserEvasionHostRemoveSpaces)
        if (httpUnixEol) add(HttpParserEvasionUnixEol)
        if (httpMethodEol) add(HttpParserEvasionMethodEol)
        if (httpMethodSpace) add(HttpParserEvasionMethodSpace)
        if (httpHostPad) add(HttpParserEvasionHostPad)
    }

fun AppSettings.activeHttpParserEvasions(): List<String> =
    activeHttpParserEvasions(
        hostMixedCase = hostMixedCase,
        domainMixedCase = domainMixedCase,
        hostRemoveSpaces = hostRemoveSpaces,
        httpMethodEol = httpMethodEol,
        httpMethodSpace = httpMethodSpace,
        httpUnixEol = httpUnixEol,
        httpHostPad = httpHostPad,
    )
