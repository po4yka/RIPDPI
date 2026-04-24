package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

fun canonicalDefaultTcpChainSteps(): List<TcpChainStepModel> =
    listOf(
        TcpChainStepModel(
            kind = TcpChainStepKind.Split,
            marker = CanonicalDefaultSplitMarker,
        ),
    )

fun AppSettings.Builder.setCanonicalDefaultStrategyChains(): AppSettings.Builder =
    setStrategyChains(canonicalDefaultTcpChainSteps(), emptyList())
