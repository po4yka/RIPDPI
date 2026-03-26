package com.poyka.ripdpi.diagnostics

import java.io.File

internal fun repoFixture(path: String): File {
    val userDir = requireNotNull(System.getProperty("user.dir"))
    var current: File? = File(userDir).absoluteFile
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
