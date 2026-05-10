package com.poyka.ripdpi.lua

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
        context.assets.open("$AssetDirectory/$ManifestFileName").use { input ->
            File(targetDir, ManifestFileName).outputStream().use(input::copyTo)
        }
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
        context.assets.open("$AssetDirectory/$fileName").use { input ->
            target.outputStream().use(input::copyTo)
        }
    }

    private fun readAssetManifest(context: Context): LuaManifest =
        context.assets.open("$AssetDirectory/$ManifestFileName").use { input ->
            parseManifest(input.bufferedReader().readText())
        }

    private fun readInstalledManifest(targetDir: File): LuaManifest? {
        val manifest = File(targetDir, ManifestFileName)
        return if (manifest.isFile) {
            parseManifest(manifest.readText())
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
