package com.poyka.ripdpi.core.detection.probe

object KnownLocalServices {
    val excludedPorts: Set<Int> =
        setOf(
            24012, // WeChat local service
            24013, // WeChat local service (secondary)
            5037, // ADB
            8610, // Device-management service
            8615, // Device-management service
            5555, // ADB wireless
        )
}
