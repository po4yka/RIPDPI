package com.poyka.ripdpi.services

import java.io.File

internal class SubprocessRelayLaunchPlanner {
    fun buildMainProcess(
        binary: File,
        spec: SubprocessSocksRelayLaunchSpec,
    ): ProcessBuilder =
        ProcessBuilder(
            buildList {
                add(binary.absolutePath)
                addAll(spec.commandArguments)
            },
        ).apply {
            redirectErrorStream(true)
            environment().putAll(spec.environment)
        }
}
