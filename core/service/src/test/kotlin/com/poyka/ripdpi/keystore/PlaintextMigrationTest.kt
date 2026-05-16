package com.poyka.ripdpi.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaintextMigrationTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun makeStore(storeDir: File): EncryptedProfileStore =
        EncryptedProfileStore(
            storageDir = storeDir,
            keyManager = FakeKeystoreKeyManager(),
        )

    @Test
    fun `migrator imports plaintext profile and encrypts it`() {
        val plaintextDir = tmpDir.newFolder("plaintext")
        val storeDir = tmpDir.newFolder("store")
        File(plaintextDir, "fixture-dev-profile-1.json").writeText("fixture-profile-json-content-1")
        val store = makeStore(storeDir)
        val migrator = PlaintextProfileMigrator(plaintextDir, store)

        val report = migrator.migrate()

        assertEquals(1, report.imported)
        val decrypted = store.read("fixture-dev-profile-1")
        assertNotNull(decrypted)
        assertEquals("fixture-profile-json-content-1", decrypted!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `migrator deletes plaintext file after encryption`() {
        val plaintextDir = tmpDir.newFolder("plaintext-del")
        val storeDir = tmpDir.newFolder("store-del")
        val plaintextFile = File(plaintextDir, "fixture-dev-profile-2.json")
        plaintextFile.writeText("fixture-profile-json-content-2")
        val store = makeStore(storeDir)
        val migrator = PlaintextProfileMigrator(plaintextDir, store)

        migrator.migrate()

        assertFalse("plaintext file must be deleted after migration", plaintextFile.exists())
    }

    @Test
    fun `migrator skips profiles that are already encrypted`() {
        val plaintextDir = tmpDir.newFolder("plaintext-skip")
        val storeDir = tmpDir.newFolder("store-skip")
        val plaintextFile = File(plaintextDir, "fixture-dev-profile-3.json")
        plaintextFile.writeText("fixture-profile-json-content-3")
        val store = makeStore(storeDir)
        // Pre-encrypt so the store already has the blob
        store.write("fixture-dev-profile-3", "fixture-already-encrypted".toByteArray())
        val migrator = PlaintextProfileMigrator(plaintextDir, store)

        val report = migrator.migrate()

        assertEquals(0, report.imported)
        assertEquals(1, report.skipped)
    }

    @Test
    fun `migrator returns zero counts when plaintext dir is empty`() {
        val plaintextDir = tmpDir.newFolder("plaintext-empty")
        val storeDir = tmpDir.newFolder("store-empty")
        val store = makeStore(storeDir)
        val migrator = PlaintextProfileMigrator(plaintextDir, store)

        val report = migrator.migrate()

        assertEquals(0, report.imported)
        assertEquals(0, report.deleted)
        assertEquals(0, report.skipped)
    }

    @Test
    fun `migrator handles multiple profiles in one run`() {
        val plaintextDir = tmpDir.newFolder("plaintext-multi")
        val storeDir = tmpDir.newFolder("store-multi")
        File(plaintextDir, "fixture-dev-profile-4.json").writeText("fixture-content-4")
        File(plaintextDir, "fixture-dev-profile-5.json").writeText("fixture-content-5")
        val store = makeStore(storeDir)
        val migrator = PlaintextProfileMigrator(plaintextDir, store)

        val report = migrator.migrate()

        assertEquals(2, report.imported)
        assertEquals(2, report.deleted)
        assertEquals(0, report.skipped)
    }
}
