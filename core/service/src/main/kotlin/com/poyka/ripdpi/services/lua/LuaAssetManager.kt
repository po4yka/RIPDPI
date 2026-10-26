package com.poyka.ripdpi.services.lua

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Path

object LuaAssetManager {
    private const val AssetDirectory = "lua"
    private const val ManifestFileName = "lua-manifest.json"
    private const val TargetDirectoryName = "lua"
    private const val UserBackupSuffix = ".user.bak"

    fun ensureExtracted(context: Context): Path {
        extractIfOutdated(context)
        return targetDirectory(context).toPath()
    }

    fun currentManifestVersion(context: Context): String = readAssetManifest(context).version

    @Synchronized
    fun extractIfOutdated(context: Context) {
        val manifest = readAssetManifest(context)
        val targetDir = targetDirectory(context).apply { mkdirs() }
        val installedManifest = readInstalledManifest(targetDir)
        val filesPresent = manifest.files.all { File(targetDir, it).isFile }
        if (installedManifest?.version == manifest.version && filesPresent) {
            return
        }

        manifest.files.forEach { fileName ->
            extractAssetFile(context, targetDir, fileName)
        }
        extractAssetFile(context, targetDir, manifest.licenseFile)
        // The manifest goes last: it marks the install complete only after every file is in place.
        writeAtomically(context, File(targetDir, ManifestFileName), "$AssetDirectory/$ManifestFileName")
    }

    private fun extractAssetFile(
        context: Context,
        targetDir: File,
        fileName: String,
    ) {
        val target = File(targetDir, fileName)
        if (target.exists()) {
            target.copyTo(File(targetDir, "$fileName$UserBackupSuffix"), overwrite = true)
        }
        writeAtomically(context, target, "$AssetDirectory/$fileName")
    }

    /** fsync + rename, so a kill mid-write leaves the previous file instead of a truncated one. */
    private fun writeAtomically(
        context: Context,
        target: File,
        assetPath: String,
    ) {
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        try {
            context.assets.open(assetPath).use { input -> input.copyTo(output) }
            atomicFile.finishWrite(output)
        } catch (error: IOException) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun readAssetManifest(context: Context): LuaManifest =
        context.assets.open("$AssetDirectory/$ManifestFileName").use { input ->
            parseManifest(input.bufferedReader().readText())
        }

    private fun readInstalledManifest(targetDir: File): LuaManifest? {
        val manifest = File(targetDir, ManifestFileName)
        // An unreadable manifest counts as not installed, so the next call re-extracts.
        return if (manifest.isFile) {
            runCatching { parseManifest(manifest.readText()) }.getOrNull()
        } else {
            null
        }
    }

    private fun parseManifest(rawJson: String): LuaManifest {
        val root = Json.parseToJsonElement(rawJson).jsonObject
        return LuaManifest(
            version = root.getValue("version").jsonPrimitive.content,
            files =
                root
                    .getValue("files")
                    .jsonArray
                    .map { element -> element.jsonPrimitive.content },
            licenseFile = root.getValue("license_file").jsonPrimitive.content,
        )
    }

    private fun targetDirectory(context: Context): File = File(context.filesDir, TargetDirectoryName)
}

private data class LuaManifest(
    val version: String,
    val files: List<String>,
    val licenseFile: String,
)
