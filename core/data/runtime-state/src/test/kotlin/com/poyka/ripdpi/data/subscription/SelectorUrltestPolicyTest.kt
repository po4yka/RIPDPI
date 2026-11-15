package com.poyka.ripdpi.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorUrltestPolicyTest {
    private fun bundle(fields: String = "") =
        """{"outbounds":[
          {"type":"shadowsocks","tag":"a","server":"a.example","server_port":443,"method":"aes-128-gcm","password":"fixture"},
          {"type":"shadowsocks","tag":"b","server":"b.example","server_port":443,"method":"aes-128-gcm","password":"fixture"},
          {"type":"urltest","tag":"auto","outbounds":["a","b"]$fields},
          {"type":"selector","tag":"select","outbounds":["auto"],"default":"auto"}]}"""

    @Test
    fun `omitted and zero cadence and tolerance use defaults with an explicit probe URL`() {
        for (fields in listOf(
            """, "url":"https://probe.example/204"""",
            """, "url":"https://probe.example/204", "interval":"0s", "tolerance":0""",
        )) {
            val result = SelectorUrltestGroupImport.import(bundle(fields), "g")
            assertTrue("$result", result is SelectorUrltestImportResult.Success)
            val policy = (result as SelectorUrltestImportResult.Success).failoverPolicy as FailoverPolicy.Urltest
            assertEquals("https://probe.example/204", policy.probeUrl)
            assertEquals(180, policy.intervalSeconds)
            assertEquals(50, policy.toleranceMs)
        }
    }

    @Test
    fun `invalid URL-test fields reject the whole import`() {
        val invalid =
            listOf(
                "",
                """, "url":""""",
                """, "url":"not-an-http-url"""",
                """, "url":"https://user:secret@probe.example/"""",
                """, "url":123""",
                """, "interval":"-1s"""",
                """, "interval":"1.5s"""",
                """, "interval":"2147483647h"""",
                """, "interval":30""",
                """, "tolerance":-1""",
                """, "tolerance":65536""",
                """, "tolerance":"50"""",
            )
        for (fields in invalid) {
            val input =
                if (fields.isEmpty() ||
                    "\"url\"" in fields
                ) {
                    fields
                } else {
                    """, "url":"https://probe.example/204"$fields"""
                }
            val result = SelectorUrltestGroupImport.import(bundle(input), "g")
            assertTrue("expected error for $fields, got $result", result is SelectorUrltestImportResult.Error)
        }
    }
}
