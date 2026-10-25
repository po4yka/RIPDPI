package com.poyka.ripdpi.services.lua

import java.io.File

private val ScriptPathListItem = Regex("""^(\s*-\s*)("?)(/[^"\n]*?)\2(\s*)$""", RegexOption.MULTILINE)

/**
 * Maps a typed script path (absolute, or relative to [baseDir]) to the [baseDir]-relative
 * form that the runtime Lua jail loads, or null when the script is outside [baseDir].
 */
fun luaJailRelativeScriptPath(
    baseDir: String,
    typedPath: String,
): String? {
    val base = File(baseDir).canonicalFile
    val typed = File(typedPath)
    val target = (if (typed.isAbsolute) typed else File(base, typedPath)).canonicalFile
    return target.toRelativeString(base).takeIf { target.startsWith(base) && it.isNotEmpty() }
}

/**
 * Rewrites absolute list-item paths inside [baseDir] in a strategy YAML to the jail-relative form.
 * Configs saved before the in-app editor stored relative paths hold absolute in-jail paths, which
 * the runtime jail rejects. Paths outside [baseDir] stay as they are, so the jail still rejects them.
 */
fun relativizeLuaScriptPaths(
    yaml: String,
    baseDir: String,
): String =
    ScriptPathListItem.replace(yaml) { match ->
        val path = match.groupValues[PathGroup]
        luaJailRelativeScriptPath(baseDir, path)
            ?.let { relative -> match.value.replaceFirst(path, relative) }
            ?: match.value
    }

private const val PathGroup = 3
