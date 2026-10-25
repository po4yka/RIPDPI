package com.poyka.ripdpi.ui.screens.settings

import java.io.File

/**
 * Maps a typed script path (absolute, or relative to [baseDir]) to the [baseDir]-relative
 * form that the runtime Lua jail loads, or null when the script is outside [baseDir].
 */
internal fun luaJailRelativeScriptPath(
    baseDir: String,
    typedPath: String,
): String? {
    val base = File(baseDir).canonicalFile
    val typed = File(typedPath)
    val target = (if (typed.isAbsolute) typed else File(base, typedPath)).canonicalFile
    return target.toRelativeString(base).takeIf { target.startsWith(base) && it.isNotEmpty() }
}
