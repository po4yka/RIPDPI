package com.poyka.ripdpi.core.detection.probe

object KnownLocalServices {
    val excludedPorts: Set<Int> =
        setOf(
            24012, // WeChat local service
            24013, // WeChat local service (secondary)
            5037, // ADB
            8610, // Samsung Knox
            8615, // Samsung Knox
            5555, // ADB wireless
        )
}
