package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.PauseAuthorityRef
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileTransactionGuard
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import javax.inject.Inject
import javax.inject.Singleton

/** Room owns commit and rollback; no coroutine suspends inside a database transaction. */
@Singleton
internal class ProfileDatabase
    @Inject
    constructor(
        private val database: RipDpiDatabase,
        private val cipher: ProfileRecordCipher,
        private val importer: LegacyProfileImporter,
    ) {
        private val initialization = Mutex()

        private val ready = MutableStateFlow(false)

        suspend fun <T> read(block: (ProfileRows) -> T): T = write(block)

        suspend fun <T> write(block: (ProfileRows) -> T): T {
            initialize()
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                transaction { rows ->
                    context.ensureActive()
                    block(rows).also { context.ensureActive() }
                }
            }
        }

        suspend fun <T> withAuthority(
            authority: PauseIntentAuthority,
            afterCommit: (T) -> Unit = {},
            block: (ProfileRows) -> T,
        ): T {
            initialize()
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                authority.intentLinearizer.serialize {
                    transaction { rows ->
                        context.ensureActive()
                        block(rows).also { context.ensureActive() }
                    }.also(afterCommit)
                }
            }
        }

        suspend fun writeOwned(
            authority: PauseIntentAuthority,
            expected: PauseAuthorityRef,
            commandId: String,
            block: (ProfileRows) -> Unit,
        ): Boolean =
            withAuthority(authority) { rows ->
                if (authority.reference() != expected ||
                    authority.snapshotAuthority().command?.commandId != commandId
                ) {
                    false
                } else {
                    block(rows)
                    true
                }
            }

        suspend fun guardedWrite(
            guard: ProfileTransactionGuard,
            authority: PauseIntentAuthority,
            block: (ProfileRows) -> Unit,
        ): Boolean {
            initialize()
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                guard.runIfCurrent {
                    authority.intentLinearizer.serialize {
                        transaction { rows ->
                            context.ensureActive()
                            block(rows)
                            context.ensureActive()
                        }
                    }
                }
            }
        }

        /** Explicit reset may discard a corrupt legacy source; its marker prevents reimport. */
        suspend fun resetProfiles(authority: PauseIntentAuthority) {
            initialization.withLock {
                withContext(Dispatchers.IO) {
                    authority.intentLinearizer.serialize {
                        transaction { rows ->
                            database.profileRecordDao().deleteAll()
                            rows.awgProfiles.deleteAll()
                            rows.rules.clear()
                            rows.settings.replace(com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue)
                            rows.records.write(MigrationNamespace, MigrationId, byteArrayOf(1))
                        }
                    }
                    importer.clearSource()
                    ready.value = true
                }
            }
        }

        fun observe(namespace: String): Flow<Map<String, ByteArray>> =
            flow {
                ready.first { it }
                emitAll(
                    database.profileRecordDao().observeNamespace(namespace).map { records ->
                        records.associate { it.id to cipher.open(it.namespace, it.id, it.payload) }
                    },
                )
            }.flowOn(Dispatchers.IO)

        fun changes(namespace: String): Flow<Unit> =
            flow {
                ready.first { it }
                emitAll(database.profileRecordDao().observeNamespace(namespace).map { Unit })
            }.flowOn(Dispatchers.IO)

        suspend fun initialize() {
            if (ready.value) return
            initialization.withLock {
                if (ready.value) return@withLock
                withContext(Dispatchers.IO) {
                    val imported = transaction { it.records.read(MigrationNamespace, MigrationId) != null }
                    if (!imported) {
                        val legacy = importer.read()
                        val context = currentCoroutineContext()
                        importer.authority.intentLinearizer.serialize {
                            transaction { rows ->
                                context.ensureActive()
                                importer.apply(rows, legacy)
                                rows.records.write(MigrationNamespace, MigrationId, byteArrayOf(1))
                                context.ensureActive()
                            }
                        }
                    }
                    transaction { it.settings.snapshot() }
                    importer.afterCommit()
                    runCatching { importer.clearSource() }.onFailure {
                        android.util.Log.w("ProfileMigration", "Legacy cleanup deferred: ${it.javaClass.simpleName}")
                    }
                    ready.value = true
                }
            }
        }

        private fun <T> transaction(block: (ProfileRows) -> T): T =
            database.runInTransaction(
                Callable {
                    val access = EncryptedProfileRecordAccess(database.profileRecordDao(), cipher)
                    block(ProfileRows(access, database.awgProfileDao(), database.profileRoutingDao()))
                },
            )

        private companion object {
            const val MigrationNamespace = "system"
            const val MigrationId = "legacy_import"
        }
    }

private class EncryptedProfileRecordAccess(
    private val dao: ProfileRecordDao,
    private val cipher: ProfileRecordCipher,
) : ProfileRecordAccess {
    override fun read(
        namespace: String,
        id: String,
    ): ByteArray? = dao.get(namespace, id)?.let { cipher.open(namespace, id, it.payload) }

    override fun entries(namespace: String): Map<String, ByteArray> =
        dao.list(namespace).associate { it.id to cipher.open(namespace, it.id, it.payload) }

    override fun keys(namespace: String): Set<String> = dao.keys(namespace).toSet()

    override fun write(
        namespace: String,
        id: String,
        payload: ByteArray,
    ) = dao.upsert(ProfileRecordEntity(namespace, id, cipher.seal(namespace, id, payload)))

    override fun delete(
        namespace: String,
        id: String,
    ) = dao.delete(namespace, id)

    override fun clear(namespace: String) = dao.deleteNamespace(namespace)
}
