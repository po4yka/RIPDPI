package com.poyka.ripdpi.pcap

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

private const val StagingRoot = "pcap-export"
private const val ProcessPrefix = "process-"
private const val DraftPrefix = "capture-"
private const val DraftSuffix = ".tmp"
private const val OwnerFile = "owner.lock"

internal object PcapExportStaging {
    private data class ProcessDirectory(
        val file: File,
        val owner: FileLock,
    )

    private val directories = mutableMapOf<File, ProcessDirectory>()

    @Synchronized
    fun create(cacheDir: File): PreparedPcapExport {
        val root = File(cacheDir, StagingRoot).canonicalFile
        Files.createDirectories(root.toPath())
        return openOwnerFile(File(root, "cleanup.lock")).use { cleanup ->
            cleanup.lock().use {
                val live = directories[root]?.takeIf { it.file.isDirectory && it.owner.isValid }
                // Only a dead process releases its ownership lock. Keep the lock file
                // separate from the draft because JNI opens and closes the draft too.
                root
                    .listFiles()
                    .orEmpty()
                    .filter { it != live?.file && it.name.startsWith(ProcessPrefix) }
                    .forEach(::reap)
                val directory = live ?: openProcessDirectory(root).also { directories[root] = it }
                PreparedPcapExport(File.createTempFile(DraftPrefix, DraftSuffix, directory.file))
            }
        }
    }

    private fun openProcessDirectory(root: File): ProcessDirectory {
        directories
            .remove(root)
            ?.owner
            ?.channel()
            ?.close()
        val directory = Files.createTempDirectory(root.toPath(), ProcessPrefix).toFile()
        val channel = openOwnerFile(File(directory, OwnerFile))
        return try {
            ProcessDirectory(directory, channel.lock())
        } catch (error: IOException) {
            channel.close()
            throw error
        }
    }

    private fun reap(directory: File) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return
        val ownerFile = File(directory, OwnerFile)
        if (Files.isSymbolicLink(ownerFile.toPath())) return
        try {
            openOwnerFile(ownerFile).use { owner ->
                owner.tryLock()?.use {
                    val drafts =
                        directory.listFiles().orEmpty().filter { file ->
                            file.name.startsWith(DraftPrefix) && file.name.endsWith(DraftSuffix)
                        }
                    if (drafts.all { it.delete() }) {
                        ownerFile.delete()
                        directory.delete()
                    }
                }
            }
        } catch (_: OverlappingFileLockException) {
            // Another owner in this JVM is still using the folder.
        } catch (_: IOException) {
            // A later process retries if the cache could not be read or removed.
        }
    }

    private fun openOwnerFile(file: File): FileChannel =
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
}
