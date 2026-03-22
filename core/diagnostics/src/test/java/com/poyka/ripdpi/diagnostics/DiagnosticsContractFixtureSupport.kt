package com.poyka.ripdpi.diagnostics

import java.io.File

internal fun repoFixture(path: String): File {
    var current: File? = File(System.getProperty("user.dir")).absoluteFile
    repeat(8) {
        val base = current ?: return@repeat
        val candidate = File(base, path)
        if (candidate.exists()) {
            return candidate
        }
        current = base.parentFile
    }
    error("Fixture not found: $path")
}
