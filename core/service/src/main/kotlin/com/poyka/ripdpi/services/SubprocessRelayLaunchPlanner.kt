package com.poyka.ripdpi.services

import java.io.File

internal class SubprocessRelayLaunchPlanner {
    fun buildMainProcess(
        binary: File,
        spec: SubprocessSocksRelayLaunchSpec,
        protectPath: String? = null,
    ): ProcessBuilder =
        ProcessBuilder(
            buildList {
                add(binary.absolutePath)
                addAll(spec.commandArguments)
            },
        ).apply {
            redirectErrorStream(true)
            environment().putAll(spec.environment)
            // The active VPN protect path is authoritative; specs never set ENV_VAR themselves.
            protectPath?.let { environment()[ActiveProtectSocketPathProvider.ENV_VAR] = it }
        }
}
