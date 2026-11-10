package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowsocksImportKeyValidationTest {
    private val validKey = "AAECAwQFBgcICQoLDA0ODw==" // Public key fixture.

    @Test
    fun `clash keeps a valid SS2022 node and legacy node with any password`() {
        val yaml = clash("2022-blake3-aes-128-gcm", validKey) + clashNode("aes-256-gcm", "pw")
        val parsed = ClashSubscriptionParser.parse(yaml, "group")
        assertEquals(2, parsed.profiles.size)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `clash rejects SS2022 node with an invalid key`() {
        val parsed = ClashSubscriptionParser.parse(clash("2022-blake3-aes-128-gcm", "bad"), "group")
        assertTrue(parsed.profiles.isEmpty())
        assertEquals(1, parsed.errors.size)
    }

    @Test
    fun `singbox keeps a valid SS2022 outbound and falls back for an invalid key`() {
        val valid = SingBoxSubscriptionParser.parse(singBox("2022-blake3-aes-128-gcm", validKey), "group")
        assertTrue((valid as SingBoxParseResult.Success).profiles.single() is ProxyProfile.Shadowsocks)

        val invalid = SingBoxSubscriptionParser.parse(singBox("2022-blake3-aes-128-gcm", "bad"), "group")
        assertFalse((invalid as SingBoxParseResult.Success).profiles.any { it is ProxyProfile.Shadowsocks })
    }

    private fun clash(
        method: String,
        password: String,
    ) = "proxies:\n" + clashNode(method, password)

    private fun clashNode(
        method: String,
        password: String,
    ) = "  - name: n-$method\n    type: ss\n    server: ss.example\n    port: 8388\n" +
        "    cipher: $method\n    password: $password\n"

    private fun singBox(
        method: String,
        password: String,
    ) = """{"type":"shadowsocks","tag":"ss","server":"ss.example","server_port":8388,""" +
        """"method":"$method","password":"$password"}"""
}
