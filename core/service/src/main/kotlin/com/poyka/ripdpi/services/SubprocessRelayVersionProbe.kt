package com.poyka.ripdpi.services

import java.io.File
import java.util.concurrent.TimeUnit

internal class SubprocessRelayVersionProbe {
    fun probe(
        binary: File,
        spec: SubprocessSocksRelayLaunchSpec,
    ): String? {
        if (spec.versionArguments.isEmpty()) {
            return null
        }
        return runCatching {
            val process =
                ProcessBuilder(
                    buildList {
                        add(binary.absolutePath)
                        addAll(spec.versionArguments)
                    },
                ).redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            process.waitFor(2, TimeUnit.SECONDS)
            output.ifBlank { null }
        }.getOrNull()
    }
}
