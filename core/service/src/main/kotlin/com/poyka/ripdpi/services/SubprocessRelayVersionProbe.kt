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
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                return@runCatching null
            }
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            output.ifBlank { null }
        }.getOrNull()
    }
}
