package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.UnreadableCredentialException
import kotlinx.coroutines.flow.map

/** Domain-test reads over test-owned stores; Room snapshot tests use the real database. */
class TestAwgProfileReadStore(
    private val dao: AwgProfileDao,
    private val credentials: AwgCredentialStore,
) : AwgProfileReadStore {
    override suspend fun load(id: String): AwgStoredRecord? = dao.getProfile(id)?.let { record(it) }

    override suspend fun list(): List<AwgStoredRecord> = dao.allProfiles().map { record(it) }

    override fun observe() = dao.observeProfiles().map { profiles -> profiles.map { record(it) } }

    private suspend fun record(profile: AwgProfileEntity): AwgStoredRecord {
        val secrets =
            try {
                Result.success(credentials.load(profile.id))
            } catch (failure: UnreadableCredentialException) {
                Result.failure(failure)
            }
        return AwgStoredRecord(profile, secrets)
    }
}

fun testAwgProfileRepository(
    dao: AwgProfileDao,
    credentialStore: AwgCredentialStore,
    profileMutations: ProfileMutationCoordinator,
): AwgProfileRepository = AwgProfileRepository(TestAwgProfileReadStore(dao, credentialStore), profileMutations)
