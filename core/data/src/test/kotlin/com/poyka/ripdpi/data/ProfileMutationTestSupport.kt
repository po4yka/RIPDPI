package com.poyka.ripdpi.data

/** Runs dispatcher-confined work inline so virtual-time tests stay deterministic. */
internal val InlineDispatchers =
    AppCoroutineDispatchers(
        kotlinx.coroutines.Dispatchers.Unconfined,
        kotlinx.coroutines.Dispatchers.Unconfined,
        kotlinx.coroutines.Dispatchers.Unconfined,
    )

internal class ProfileMutationTestFixture(
    initialAuthority: PauseAuthorityState? = null,
) : java.io.Closeable {
    val persistence = TestPauseAuthorityPersistence(initialAuthority)
    var pauseAuthority = testPauseAuthority(persistence, initialize = false)
        private set
    var room =
        com.poyka.ripdpi.data.testing.RoomProfileFixture(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext(),
            pauseAuthority,
        )
        private set
    val settings get() = room.settings
    val relayProfiles get() = room.relayProfiles
    val relayCredentials get() = room.relayCredentials
    val warpProfiles get() = room.warpProfiles
    val warpCredentials get() = room.warpCredentials
    val warpEndpoints get() = room.warpEndpoints
    val awgProfiles get() = room.awgProfiles
    val awgCredentials get() = room.awgCredentials
    val awgRepository get() = room.awgRepository
    val xrayMetadata get() = room.xrayMetadata
    val xraySecrets get() = room.xraySecrets
    val xraySelection get() = room.xraySelection
    val profileSelection get() = room.profileSelection
    val groupCatalog get() = room.groupCatalog
    val groups get() = room.groups
    val selectorChoice get() = room.selectorActiveGroup
    val selectorSelections get() = room.selectorSelections
    val stores get() = room.stores
    val mutationGeneration get() = room.mutationGeneration

    suspend fun initialize() = room.initialize()

    fun coordinator() = room.coordinator

    suspend fun reopen() {
        pauseAuthority = testPauseAuthority(persistence, initialize = false)
        room = room.reopen(pauseAuthority)
        room.initialize()
    }

    suspend fun snapshot(): StoredProfileDatabase =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            room.database.runInTransaction(
                java.util.concurrent.Callable {
                    StoredProfileDatabase(
                        rows("profile_records"),
                        rows("awg_profiles"),
                        rows("routing_rules"),
                    )
                },
            )
        }

    private fun rows(table: String): List<List<String?>> =
        room.database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).map { index ->
                            when (cursor.getType(index)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> {
                                    null
                                }

                                android.database.Cursor.FIELD_TYPE_BLOB -> {
                                    java.util.Base64.getEncoder().encodeToString(
                                        cursor.getBlob(index),
                                    )
                                }

                                else -> {
                                    cursor.getString(index)
                                }
                            }
                        },
                    )
                }
            }
        }

    fun failWrite(
        namespace: String,
        failure: Throwable = IllegalStateException("injected profile write failure"),
    ) {
        room.beforeSeal = { selected, _ -> if (selected == namespace) throw failure }
    }

    fun clearFaults() {
        room.beforeSeal = null
        room.beforeOpen = null
    }

    override fun close() = room.close()
}

internal data class StoredProfileDatabase(
    val records: List<List<String?>>,
    val awg: List<List<String?>>,
    val rules: List<List<String?>>,
)

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
abstract class RoomProfileTest {
    private val opened = mutableListOf<ProfileMutationTestFixture>()

    internal fun fixture(initialAuthority: PauseAuthorityState? = null): ProfileMutationTestFixture =
        ProfileMutationTestFixture(initialAuthority).also { opened += it }

    @org.junit.After
    fun closeDatabases() {
        opened.forEach { it.close() }
    }
}

internal class ProfileWriteBarrier(
    private val namespace: String,
) {
    private val entered = java.util.concurrent.CountDownLatch(1)
    private val released = java.util.concurrent.CountDownLatch(1)

    fun beforeSeal(
        selected: String,
        id: String,
    ) {
        if (selected != namespace) return
        entered.countDown()
        check(
            released.await(10, java.util.concurrent.TimeUnit.SECONDS),
        ) { "Write barrier for $selected/$id was not released" }
    }

    suspend fun awaitEntry() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Write did not reach barrier" }
        }

    fun release() {
        released.countDown()
    }
}

internal fun unreadableFixture() = UnreadableCredentialException(javax.crypto.AEADBadTagException("fixture"))

internal suspend fun ProfileMutationTestFixture.corruptCredential(
    namespace: String,
    id: String,
) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    room.database.profileRecordDao().upsert(
        com.poyka.ripdpi.data.persistence
            .ProfileRecordEntity(namespace, id, byteArrayOf()),
    )
}
