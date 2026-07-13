package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarpProfileActivationServiceTest {
    @Test
    fun `late auth failure cannot resurrect profile deleted under mutation lock`() =
        runTest {
            val profile = WarpProfile(id = "deleted-profile")
            val profiles = RecordingWarpProfileStore().apply { save(profile) }
            val lock = WarpStoreMutationLock()
            val service = DefaultWarpProfileActivationService(TestAppSettingsRepository(), profiles, lock)
            lock.mutex.lock()
            val markJob = backgroundScope.launch { service.markProfileNeedsAttention(profile) }
            runCurrent()

            profiles.remove(profile.id)
            lock.mutex.unlock()
            markJob.join()

            assertNull(profiles.load(profile.id))
        }

    private class RecordingWarpProfileStore : WarpProfileStore {
        private val profiles = mutableMapOf<String, WarpProfile>()
        private var activeId: String? = null

        override suspend fun load(profileId: String): WarpProfile? = profiles[profileId]

        override suspend fun loadAll(): List<WarpProfile> = profiles.values.toList()

        override suspend fun save(profile: WarpProfile) {
            profiles[profile.id] = profile
        }

        override suspend fun remove(profileId: String) {
            profiles.remove(profileId)
        }

        override suspend fun activeProfileId(): String? = activeId

        override suspend fun setActiveProfileId(profileId: String?) {
            activeId = profileId
        }

        override suspend fun clearAll() {
            profiles.clear()
            activeId = null
        }
    }
}
