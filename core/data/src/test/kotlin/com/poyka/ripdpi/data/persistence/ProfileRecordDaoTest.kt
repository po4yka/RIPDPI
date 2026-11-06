package com.poyka.ripdpi.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileRecordDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `failed transaction preserves every namespace`() =
        runTest {
            withContext(Dispatchers.IO) {
                val database = Room.inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java).build()
                try {
                    val dao = database.profileRecordDao()
                    dao.upsert(record("profiles", "one", "original"))
                    dao.upsert(record("credentials", "one", "secret"))
                    val awgDao = database.awgProfileDao()
                    val awg = AwgProfileEntity("awg", "Original AWG", "{}", 42L)
                    awgDao.upsertProfile(awg)

                    assertThrows(IllegalStateException::class.java) {
                        database.runInTransaction {
                            dao.upsert(record("profiles", "one", "changed"))
                            dao.deleteNamespace("credentials")
                            dao.upsert(record("selection", "active", "one"))
                            awgDao.deleteAll()
                            error("abort transaction")
                        }
                    }

                    assertPayload("original", dao.get("profiles", "one"))
                    assertPayload("secret", dao.get("credentials", "one"))
                    assertNull(dao.get("selection", "active"))
                    assertEquals(awg, awgDao.getProfile("awg"))
                    assertPayload("original", dao.observeNamespace("profiles").first().single())
                } finally {
                    database.close()
                }
            }
        }

    @Test
    fun `committed records survive reopening and keep namespace boundaries`() =
        runTest {
            withContext(Dispatchers.IO) {
                val name = "profile-records-${System.nanoTime()}.db"
                try {
                    Room.databaseBuilder(context, RipDpiDatabase::class.java, name).build().useDatabase { database ->
                        val dao = database.profileRecordDao()
                        database.runInTransaction {
                            dao.upsert(record("profiles", "one", "profile"))
                            dao.upsert(record("credentials", "one", "secret"))
                            dao.upsert(record("selection", "active", "one"))
                        }
                    }
                    Room.databaseBuilder(context, RipDpiDatabase::class.java, name).build().useDatabase { database ->
                        val dao = database.profileRecordDao()
                        assertPayload("profile", dao.list("profiles").single())
                        assertPayload("secret", dao.get("credentials", "one"))
                        assertPayload("one", dao.get("selection", "active"))
                        assertEquals(listOf("one"), dao.observeNamespace("profiles").first().map { it.id })
                    }
                } finally {
                    context.deleteDatabase(name)
                }
            }
        }

    @Test
    fun `upsert and deletion affect only the selected records`() =
        runTest {
            withContext(Dispatchers.IO) {
                Room.inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java).build().useDatabase { database ->
                    val dao = database.profileRecordDao()
                    dao.upsert(record("profiles", "two", "second"))
                    dao.upsert(record("profiles", "one", "first"))
                    dao.upsert(record("profiles", "one", "updated"))
                    dao.upsert(record("credentials", "one", "secret"))
                    assertEquals(listOf("one", "two"), dao.keys("profiles"))
                    assertPayload("updated", dao.get("profiles", "one"))

                    dao.delete("profiles", "one")
                    assertEquals(listOf("two"), dao.list("profiles").map { it.id })
                    dao.deleteNamespace("profiles")
                    assertEquals(emptyList<ProfileRecordEntity>(), dao.list("profiles"))
                    assertPayload("secret", dao.get("credentials", "one"))
                }
            }
        }

    private inline fun RipDpiDatabase.useDatabase(block: (RipDpiDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun record(
        namespace: String,
        id: String,
        payload: String,
    ) = ProfileRecordEntity(namespace, id, payload.toByteArray())

    private fun assertPayload(
        expected: String,
        actual: ProfileRecordEntity?,
    ) {
        assertArrayEquals(expected.toByteArray(), actual?.payload)
    }
}
