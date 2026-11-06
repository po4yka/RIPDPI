package com.poyka.ripdpi.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.data.persistence.KeystoreProfileRecordCipher
import com.poyka.ripdpi.data.persistence.ProfileRecordEntity
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfilePersistenceInstrumentedTest {
    @Test
    fun encryptedRecordsSurviveReopenAndFailedTransactionWithAndroidKeystore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "profile-persistence-test-${UUID.randomUUID()}.db"
        val marker = "fixture-profile-record-secret"
        val payload = marker.encodeToByteArray()
        val cipher = KeystoreProfileRecordCipher()
        var database = Room.databaseBuilder(context, RipDpiDatabase::class.java, name).build()
        try {
            database.runInTransaction {
                database.profileRecordDao().upsert(
                    ProfileRecordEntity("credentials", "profile", cipher.seal("credentials", "profile", payload)),
                )
            }
            val failedWrite =
                runCatching {
                    database.runInTransaction {
                        database.profileRecordDao().deleteAll()
                        error("Fixture transaction failure")
                    }
                }
            assertTrue(failedWrite.isFailure)
            assertNotNull(database.profileRecordDao().get("credentials", "profile"))
            for (suffix in listOf("", "-wal", "-shm")) {
                val file = context.getDatabasePath(name + suffix)
                if (file.exists()) {
                    assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
                }
            }

            database.close()
            database = Room.databaseBuilder(context, RipDpiDatabase::class.java, name).build()
            val saved = checkNotNull(database.profileRecordDao().get("credentials", "profile")).payload
            val reopenedCipher = KeystoreProfileRecordCipher()
            assertArrayEquals(payload, reopenedCipher.open("credentials", "profile", saved))
            assertTrue(
                runCatching { reopenedCipher.open("credentials", "another-profile", saved) }
                    .exceptionOrNull() is GeneralSecurityException,
            )
            val tampered = saved.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertTrue(
                runCatching { reopenedCipher.open("credentials", "profile", tampered) }
                    .exceptionOrNull() is GeneralSecurityException,
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
