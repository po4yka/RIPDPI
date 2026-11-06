package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.UnreadableCredentialException
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileReadStore
import com.poyka.ripdpi.data.awg.AwgStoredRecord
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomAwgProfileReadStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
        private val dao: AwgProfileDao,
    ) : AwgProfileReadStore {
        override suspend fun load(id: String) =
            database.read { rows -> rows.awgProfiles.getProfile(id)?.let { rows.capture(it) } }

        override suspend fun list() = database.read { rows -> rows.awgProfiles.allProfiles().map { rows.capture(it) } }

        override fun observe() =
            combine(dao.observeProfiles(), database.changes(AwgCredentialRows.Namespace)) { _, _ -> list() }
                .distinctUntilChanged()

        private fun ProfileRows.capture(profile: AwgProfileEntity): AwgStoredRecord {
            val secrets =
                try {
                    Result.success(awgCredentials.load(profile.id))
                } catch (error: UnreadableCredentialException) {
                    Result.failure(error)
                }
            return AwgStoredRecord(profile, secrets)
        }
    }
