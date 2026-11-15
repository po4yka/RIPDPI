package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorInitialPreferenceImportTest {
    @Test
    fun `nested selector default is saved as a concrete initial preference`() {
        val payload =
            """{"outbounds":[
              {"type":"shadowsocks","tag":"a","server":"a.example","server_port":443,"method":"aes-128-gcm","password":"fixture"},
              {"type":"shadowsocks","tag":"b","server":"b.example","server_port":443,"method":"aes-128-gcm","password":"fixture"},
              {"type":"selector","tag":"inner","outbounds":["a","b"],"default":"b"},
              {"type":"selector","tag":"select","outbounds":["inner","a"],"default":"inner"}]}"""
        val imported = SelectorUrltestGroupImport.import(payload, "g")
        assertTrue(imported is SelectorUrltestImportResult.Success)
        val group = (imported as SelectorUrltestImportResult.Success).group!!
        val saved = RipDpiJson.parseToJsonElement(RipDpiJson.encodeToString(ProxyGroup.serializer(), group)).jsonObject
        assertEquals(
            group.members.single { it.displayName == "b" }.id,
            saved["initialMemberId"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(group, RipDpiJson.decodeFromString(ProxyGroup.serializer(), saved.toString()))
    }

    @Test
    fun `old groups without an imported preference still load with no choice`() {
        val legacy = """{"id":"g","name":"Legacy","type":"SUBSCRIPTION","order":0,"isSelector":true}"""
        val group = RipDpiJson.decodeFromString(ProxyGroup.serializer(), legacy)
        assertNull(group.initialMemberId)
        assertTrue(
            "initialMemberId" !in
                RipDpiJson.parseToJsonElement(RipDpiJson.encodeToString(ProxyGroup.serializer(), group)).jsonObject,
        )
    }
}
