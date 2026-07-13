package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.serialization.RipDpiJson
import com.poyka.ripdpi.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingProxyImportStoreTest {
    @Test
    fun `credential-bearing profile stays outside serialized route`() {
        val privateKey = "synthetic-key-material-" + "x".repeat(64)
        val passphrase = "correct horse battery staple"
        val profile = sshProfile(privateKey = privateKey, passphrase = passphrase)
        val store = PendingProxyImportStore()

        val route = Route.ProfileImportConfirm(importToken = store.stage(profile))
        val serializedRoute = RipDpiJson.encodeToString(Route.ProfileImportConfirm.serializer(), route)

        assertTrue(serializedRoute.contains(route.importToken))
        assertFalse(serializedRoute.contains(profile.id))
        assertFalse(serializedRoute.contains(privateKey))
        assertFalse(serializedRoute.contains(passphrase))
        assertTrue(serializedRoute.length < 128)
        assertEquals(profile, store.claim(route.importToken))
    }

    @Test
    fun `large raw config does not grow serialized route`() {
        val rawConfig = "x".repeat(1_048_576)
        val profile =
            ProxyProfile.RawConfig(
                id = "raw-profile-id",
                displayName = "Raw",
                groupId = "",
                config = rawConfig,
            )
        val store = PendingProxyImportStore()

        val route = Route.ProfileImportConfirm(importToken = store.stage(profile))
        val serializedRoute = RipDpiJson.encodeToString(Route.ProfileImportConfirm.serializer(), route)

        assertTrue(serializedRoute.length < 128)
        assertFalse(serializedRoute.contains(rawConfig.take(256)))
        assertEquals(profile, store.claim(route.importToken))
    }

    @Test
    fun `claim is one-shot`() {
        val store = PendingProxyImportStore()
        val profile = sshProfile()
        val token = store.stage(profile)

        assertEquals(profile, store.claim(token))
        assertNull(store.claim(token))
        assertFalse(store.contains(token))
    }

    @Test
    fun `store evicts oldest profile when capacity is exceeded`() {
        val store = PendingProxyImportStore(capacity = 2)
        val firstToken = store.stage(sshProfile(id = "first"))
        val secondToken = store.stage(sshProfile(id = "second"))
        val thirdToken = store.stage(sshProfile(id = "third"))

        assertNull(store.claim(firstToken))
        assertEquals("second", store.claim(secondToken)?.id)
        assertEquals("third", store.claim(thirdToken)?.id)
    }

    @Test
    fun `expired profile is not restored`() {
        var nowNanos = 0L
        val store =
            PendingProxyImportStore(
                ttlNanos = 10,
                nanoTime = { nowNanos },
            )
        val token = store.stage(sshProfile())

        nowNanos = 11

        assertNull(store.claim(token))
    }

    private fun sshProfile(
        id: String = "ssh-profile-id",
        privateKey: String = "private-key",
        passphrase: String = "passphrase",
    ) = ProxyProfile.Ssh(
        id = id,
        displayName = "SSH",
        groupId = "",
        server = "ssh.example.com",
        serverPort = 22,
        username = "alice",
        authType = "private_key",
        privateKey = privateKey,
        privateKeyPassphrase = passphrase,
    )
}
