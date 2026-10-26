package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AwgHeaderProtectionKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class AwgProfileRepositoryRoomTest : AwgProfileRepositoryRoomFixture() {
    @Test
    fun `save mints an opaque profile id and load round-trips every field`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())

            // The id is opaque and NOT derived from the endpoint (privacy invariant).
            assertTrue("id must use the opaque awg- prefix", id.startsWith("awg-"))
            assertFalse("id must not leak the endpoint host", id.contains("vpn.example.com"))
            assertFalse("id must not leak the endpoint port", id.contains("51820"))

            val loaded = repository.load(id)!!
            assertEquals("home", loaded.name)
            assertEquals(id, loaded.id)
            // The loaded request is stamped with the stable row id as its profileId.
            assertEquals(id, loaded.request.profileId)
            assertEquals("vpn.example.com", loaded.request.endpointHost)
            assertEquals(51820, loaded.request.endpointPort)
            assertEquals("privkey==", loaded.request.privateKey)
            assertEquals("psk==", loaded.request.presharedKey)
            assertEquals("10.8.0.2/32", loaded.request.interfaceAddressV4)
            assertEquals(1280, loaded.request.mtu)
            assertEquals(25, loaded.request.persistentKeepalive)
            assertEquals(4, loaded.request.obfuscation.jc)
            assertEquals(70, loaded.request.obfuscation.jmax)
            assertEquals("1000000001", loaded.request.obfuscation.h1)
            assertEquals("<b 0xdeadbeef>", loaded.request.obfuscation.i1)
        }

    @Test
    fun `a profile whose secrets cannot be decrypted still lists and accepts re-entered keys`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())
            credentialStore.unreadable += id

            val loaded = requireNotNull(repository.load(id))
            assertEquals("", loaded.request.privateKey)
            assertEquals(listOf(id), repository.observeProfiles().first().map { it.id })

            repository.save("home", sampleRequest().copy(privateKey = "re-entered=="), existingId = id)
            assertEquals("re-entered==", repository.load(id)?.request?.privateKey)
        }

    @Test
    fun `numeric header rows are quarantined without stopping valid profile observations or erasing keys`() =
        runTest {
            val oldId = repository.save("old contract", sampleRequest())
            val original = requireNotNull(dao.getProfile(oldId))
            val oldBlob =
                original.requestJson
                    .replace("\"wireProfile\":\"awg-3.1-ranges-v1\",", "")
                    .replace(Regex("\"h([1-4])\":\"([0-9]+)\""), "\"h$1\":$2")
            val oldRow = original.copy(requestJson = oldBlob)
            dao.upsertProfile(oldRow)
            assertNull(repository.load(oldId))
            repository.observeProfiles().test {
                assertTrue(awaitItem().isEmpty())
                val validId = repository.save("current contract", sampleRequest())
                assertEquals(listOf(validId), awaitItem().map { it.id })
                assertEquals(oldRow, dao.getProfile(oldId))
                assertEquals("privkey==", credentialStore.load(oldId)?.privateKey)
                assertEquals("psk==", credentialStore.load(oldId)?.presharedKey)
                repository.save("reimported", sampleRequest(), existingId = oldId)
                assertEquals(setOf(oldId, validId), awaitItem().map { it.id }.toSet())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `re-saving under the existing id reuses the row and keeps a stable id`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())

            val reusedId =
                repository.save(
                    name = "home renamed",
                    request = sampleRequest().copy(endpointPort = 4443),
                    existingId = id,
                )

            assertEquals("re-save must reuse the same stable id", id, reusedId)
            val all = repository.observeProfiles().first()
            assertEquals("re-save must update in place, not duplicate", 1, all.size)
            assertEquals("home renamed", all[0].name)
            assertEquals(4443, all[0].request.endpointPort)
            // The re-loaded request still carries the stable id as profileId.
            assertEquals(id, all[0].request.profileId)
        }

    @Test
    fun `failed credential update restores the previous profile snapshot`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())
            credentialStore.failNextSaveAfterWrite = true

            val error =
                runCatching {
                    repository.save(
                        name = "changed",
                        request = sampleRequest().copy(privateKey = "changed-private-key", endpointPort = 4443),
                        existingId = id,
                    )
                }.exceptionOrNull()

            assertNotNull(error)
            val restored = repository.load(id)!!
            assertEquals("home", restored.name)
            assertEquals(51820, restored.request.endpointPort)
            assertEquals("privkey==", restored.request.privateKey)
        }

    @Test
    fun `failed Room update restores the previous profile snapshot`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())
            failingDao.failNextUpsertAfterWrite = true

            val error =
                runCatching {
                    repository.save(
                        name = "changed",
                        request = sampleRequest().copy(privateKey = "changed-private-key", endpointPort = 4443),
                        existingId = id,
                    )
                }.exceptionOrNull()

            assertNotNull(error)
            val restored = repository.load(id)!!
            assertEquals("home", restored.name)
            assertEquals(51820, restored.request.endpointPort)
            assertEquals("privkey==", restored.request.privateKey)
        }

    @Test
    fun `the persisted blob never pins a specific activation id`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest(profileId = "awg-should-be-blanked"))

            val row = dao.getProfile(id)!!
            // The stored JSON blanks profileId; the row id is the single source of truth.
            assertFalse(
                "the serialized blob must blank profileId",
                row.requestJson.contains("awg-should-be-blanked"),
            )
            assertEquals(id, repository.load(id)!!.request.profileId)
        }

    @Test
    fun `the persisted blob holds no private or preshared key material and load re-injects it`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())

            // The Room blob must contain NO secret material -- it lives encrypted elsewhere.
            val row = dao.getProfile(id)!!
            assertFalse("the blob must not persist the private key", row.requestJson.contains("privkey=="))
            assertFalse("the blob must not persist the preshared key", row.requestJson.contains("psk=="))

            // The secrets are sealed in the credential store, keyed by the stable id.
            assertEquals("privkey==", credentialStore.load(id)?.privateKey)
            assertEquals("psk==", credentialStore.load(id)?.presharedKey)

            // load() re-injects the secrets so the round-tripped request equals the original.
            val loaded = repository.load(id)!!
            assertEquals(sampleRequest().copy(profileId = id), loaded.request)
        }

    @Test
    fun `delete removes the encrypted secrets alongside the row`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())
            assertNotNull(credentialStore.load(id))

            repository.delete(id)

            assertNull("delete must also clear the sealed secrets", credentialStore.load(id))
        }

    @Test
    fun `deleteAll removes every persisted profile row`() =
        runTest {
            repository.save(name = "home", request = sampleRequest())
            repository.save(name = "work", request = sampleRequest().copy(endpointPort = 443))

            dao.deleteAll()

            assertTrue(repository.observeProfiles().first().isEmpty())
        }

    @Test
    fun `a stored blob with an unknown extra key decodes without throwing`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())

            // Simulate a row written by a FUTURE app version carrying an additive field.
            val row = dao.getProfile(id)!!
            val forwardCompatJson =
                row.requestJson.replaceFirst("{", """{"unknownFutureField":"surprise",""")
            dao.upsertProfile(row.copy(requestJson = forwardCompatJson))

            // Decode must tolerate the unknown key instead of throwing into a silent Failed.
            val loaded = repository.load(id)
            assertNotNull("forward-compatible decode must not throw", loaded)
            assertEquals("vpn.example.com", loaded!!.request.endpointHost)
            // Secrets are still re-injected from the credential store on the rehydrated row.
            assertEquals("privkey==", loaded.request.privateKey)
        }

    @Test
    fun `observeProfiles emits on save and delete`() =
        runTest {
            repository.observeProfiles().test {
                assertEquals(emptyList<String>(), awaitItem().map { it.name })

                val id = repository.save(name = "first", request = sampleRequest())
                assertEquals(listOf("first"), awaitItem().map { it.name })

                repository.delete(id)
                assertEquals(emptyList<String>(), awaitItem().map { it.name })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `delete of an unknown id is a no-op`() =
        runTest {
            repository.delete("awg-does-not-exist")
            assertNull(repository.load("awg-does-not-exist"))
        }

    @Test
    fun `an AmneziaVPN 5x profile keeps its header protection key out of the Room blob`() =
        runTest {
            val key = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="
            val request =
                sampleRequest().copy(
                    obfuscation =
                        sampleRequest().obfuscation.copy(
                            s1 = 12,
                            s2 = 12,
                            s3 = 12,
                            s4 = 12,
                            v3 =
                                AmneziaWgV3Parameters(
                                    headerProtectionKey = AwgHeaderProtectionKey(key),
                                    contentPaddingAddition = "10-100",
                                    randomTrailers = true,
                                ),
                        ),
                )

            val id = repository.save(name = "amnezia5", request = request)

            val row = dao.getProfile(id)!!
            assertFalse("the blob must not persist the header protection key", row.requestJson.contains(key))
            assertFalse(row.requestJson.contains("AwMDAwMD"))
            val loaded = repository.load(id)!!.request
            assertEquals(key, loaded.obfuscation.v3.headerProtectionKey.value)
            assertEquals(request.copy(profileId = id), loaded)
        }
}
