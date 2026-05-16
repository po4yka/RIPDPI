package com.poyka.ripdpi.keystore

import java.io.File

/**
 * Result of a plaintext-profile migration run.
 *
 * [imported] — number of plaintext profiles encrypted and stored.
 * [deleted] — number of plaintext files removed after successful encryption.
 * [skipped] — number of files skipped because they were already encrypted.
 */
data class MigrationReport(
    val imported: Int,
    val deleted: Int,
    val skipped: Int,
)

/**
 * Scans [plaintextDir] for `*.json` profile files, encrypts each via
 * [store], and deletes the plaintext originals.
 *
 * Files that already have a corresponding `.enc` blob in [store] are skipped
 * (idempotent; safe to call multiple times).
 */
class PlaintextProfileMigrator(
    private val plaintextDir: File,
    private val store: EncryptedProfileStore,
) {
    fun migrate(): MigrationReport {
        val jsonFiles = plaintextDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        var imported = 0
        var deleted = 0
        var skipped = 0

        for (file in jsonFiles) {
            val profileId = file.nameWithoutExtension
            if (store.contains(profileId)) {
                skipped++
                continue
            }
            val plaintext = file.readBytes()
            store.write(profileId, plaintext)
            imported++
            if (file.delete()) {
                deleted++
            }
        }

        return MigrationReport(imported = imported, deleted = deleted, skipped = skipped)
    }
}
