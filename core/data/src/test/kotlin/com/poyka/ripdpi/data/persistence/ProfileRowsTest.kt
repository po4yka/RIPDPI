package com.poyka.ripdpi.data.persistence

import android.database.sqlite.SQLiteException
import com.poyka.ripdpi.data.UnreadableCredentialException
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
class ProfileRowsTest {
    private val records = FakeProfileRecordAccess()

    @Test
    fun `endpoint composite keys keep colon and escaped profile names separate`() {
        val endpoints = WarpEndpointRows(records)
        val first = endpoint("corp:wifi", "home")
        val second = endpoint("corp", "wifi:home")
        val escaped = endpoint("corp\"\\", "wifi:home")
        endpoints.save(first)
        endpoints.save(second)
        endpoints.save(escaped)

        assertEquals(first, endpoints.load("corp:wifi", "home"))
        assertEquals(second, endpoints.load("corp", "wifi:home"))
        assertEquals(listOf(escaped), endpoints.loadAll("corp\"\\"))

        endpoints.clearProfile("corp")

        assertNull(endpoints.load("corp", "wifi:home"))
        assertEquals(first, endpoints.load("corp:wifi", "home"))
        assertEquals(escaped, endpoints.load("corp\"\\", "wifi:home"))
    }

    @Test
    fun `clearing WARP does not clear another provider selection`() {
        val profiles = WarpProfileRows(records)
        profiles.save(WarpProfile(id = "work"))
        profiles.setActiveProfileId("work")
        records.write("profile_selection", "xray", "\"other\"".encodeToByteArray())

        profiles.clearAll()

        assertEquals(emptyList<WarpProfile>(), profiles.loadAll())
        assertNull(profiles.activeProfileId())
        assertEquals("\"other\"", records.read("profile_selection", "xray")?.decodeToString())
    }

    @Test
    fun `WARP list keeps readable credentials while direct read reports key loss`() {
        val rows = WarpCredentialRows(records)
        rows.save("a", credentials("untrusted-payload-id"))
        rows.save("broken", credentials("broken"))
        rows.save("z", credentials("z"))
        val keyLoss = GeneralSecurityException("Fixture key unavailable")
        records.failures[WarpCredentialRows.Namespace to "broken"] = keyLoss

        assertEquals(listOf("a", "z"), rows.loadAll().map(WarpCredentials::profileId))
        val failure = assertThrows(UnreadableCredentialException::class.java) { rows.load("broken") }
        assertSame(keyLoss, failure.cause)
    }

    @Test
    fun `credential read does not mask a database failure as missing key material`() {
        val failure = SQLiteException("Fixture database failure")
        records.failures[AwgCredentialRows.Namespace to "work"] = failure

        val thrown = assertThrows(SQLiteException::class.java) { AwgCredentialRows(records).load("work") }

        assertSame(failure, thrown)
    }

    private fun endpoint(
        profileId: String,
        scope: String,
    ) = WarpEndpointCacheEntry(profileId = profileId, networkScopeKey = scope, port = 2408, updatedAtEpochMillis = 1)

    private fun credentials(profileId: String) =
        WarpCredentials(
            profileId = profileId,
            deviceId = "Fixture device",
            accessToken = "Fixture token",
            updatedAtEpochMillis = 1,
        )
}

private class FakeProfileRecordAccess : ProfileRecordAccess {
    private val values = mutableMapOf<Pair<String, String>, ByteArray>()
    val failures = mutableMapOf<Pair<String, String>, Exception>()

    override fun read(
        namespace: String,
        id: String,
    ): ByteArray? {
        failures[namespace to id]?.let { throw it }
        return values[namespace to id]
    }

    override fun keys(namespace: String): Set<String> =
        values.keys
            .filter {
                it.first == namespace
            }.map { it.second }
            .toSet()

    override fun entries(namespace: String): Map<String, ByteArray> =
        keys(namespace).associateWith { id -> checkNotNull(read(namespace, id)) }

    override fun write(
        namespace: String,
        id: String,
        payload: ByteArray,
    ) {
        values[namespace to id] = payload
    }

    override fun delete(
        namespace: String,
        id: String,
    ) {
        values.remove(namespace to id)
    }

    override fun clear(namespace: String) {
        values.keys.removeAll { it.first == namespace }
    }
}
