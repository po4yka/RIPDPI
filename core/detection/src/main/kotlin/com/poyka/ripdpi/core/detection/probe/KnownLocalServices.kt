package com.poyka.ripdpi.core.detection.probe

object KnownLocalServices {
    val excludedPorts: Set<Int> =
        setOf(
            24012,
            24013,
            5037,
            8610,
            8615,
            5555,
        )
}
