package com.poyka.ripdpi.services.lua

import java.io.File

// The key may open a list item ("- script_paths:"); its column is then after the dash.
private val ScriptPathsKey = Regex("""^(\s*(?:-\s+)?)script_paths:\s*$""")
private val AbsolutePathListItem = Regex("""^(\s*)-\s*("?)(/[^"]*?)\2\s*$""")
private const val IndentGroup = 1
private const val PathGroup = 3

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
 * Rewrites absolute items of `script_paths:` blocks that lie inside [baseDir] to the jail-relative
 * form. Configs saved before the in-app editor stored relative paths hold absolute in-jail paths,
 * which the runtime jail rejects. Paths outside [baseDir] stay as they are, so the jail still
 * rejects them. Other YAML lists are not touched.
 */
fun relativizeLuaScriptPaths(
    yaml: String,
    baseDir: String,
): String {
    var keyIndent: Int? = null
    return yaml.lines().joinToString("\n") { line ->
        ScriptPathsKey.matchEntire(line)?.let { key ->
            keyIndent = key.groupValues[IndentGroup].length
            return@joinToString line
        }
        val item = AbsolutePathListItem.matchEntire(line)
        val inBlock = keyIndent?.let { indent -> line.isBlank() || line.isScriptPathsItem(indent) } == true
        if (!inBlock) keyIndent = null
        val path = item?.groupValues?.get(PathGroup)
        val relative = if (inBlock && path != null) luaJailRelativeScriptPath(baseDir, path) else null
        if (path == null || relative == null) line else line.replaceFirst(path, relative)
    }
}

private fun String.isScriptPathsItem(keyIndent: Int): Boolean =
    trimStart().startsWith("-") && length - trimStart().length >= keyIndent
