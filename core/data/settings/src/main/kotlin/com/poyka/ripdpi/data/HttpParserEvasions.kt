package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val HttpParserEvasionHostMixedCase = "host_mixed_case"
const val HttpParserEvasionDomainMixedCase = "domain_mixed_case"
const val HttpParserEvasionHostRemoveSpaces = "host_remove_spaces"
const val HttpParserEvasionMethodEol = "method_eol"
const val HttpParserEvasionMethodSpace = "method_space"
const val HttpParserEvasionUnixEol = "unix_eol"
const val HttpParserEvasionHostPad = "host_pad"
const val HttpParserEvasionHostExtraSpace = "host_extra_space"
const val HttpParserEvasionHostTab = "host_tab"

fun activeHttpParserEvasions(
    hostMixedCase: Boolean,
    domainMixedCase: Boolean,
    hostRemoveSpaces: Boolean,
    httpMethodEol: Boolean,
    httpMethodSpace: Boolean,
    httpUnixEol: Boolean,
    httpHostPad: Boolean,
    httpHostExtraSpace: Boolean = false,
    httpHostTab: Boolean = false,
): List<String> =
    buildList {
        if (hostMixedCase) add(HttpParserEvasionHostMixedCase)
        if (domainMixedCase) add(HttpParserEvasionDomainMixedCase)
        if (hostRemoveSpaces) add(HttpParserEvasionHostRemoveSpaces)
        if (httpUnixEol) add(HttpParserEvasionUnixEol)
        if (httpMethodEol) add(HttpParserEvasionMethodEol)
        if (httpMethodSpace) add(HttpParserEvasionMethodSpace)
        if (httpHostPad) add(HttpParserEvasionHostPad)
        if (httpHostExtraSpace) add(HttpParserEvasionHostExtraSpace)
        if (httpHostTab) add(HttpParserEvasionHostTab)
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
        httpHostExtraSpace = httpHostExtraSpace,
        httpHostTab = httpHostTab,
    )
