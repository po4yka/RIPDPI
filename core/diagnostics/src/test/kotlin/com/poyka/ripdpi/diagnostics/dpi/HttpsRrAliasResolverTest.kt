package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.util.Base64

class HttpsRrAliasResolverTest {
    @Test
    fun followsAliasOverActualDohBeforeSelectingConfig() =
        runTest {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val query = Message(requireNotNull(request.body).toByteArray())
                            val host = query.question.name.toString(true)
                            val rdata = if (host == "origin.example") "0 alias.example." else serviceConfig()
                            return MockResponse
                                .Builder()
                                .addHeader("Content-Type", "application/dns-message")
                                .body(Buffer().write(dnsResponse(query, rdata)))
                                .build()
                        }
                    }
                server.start()
                val resolver =
                    HttpsRrResolver(
                        query = DohWireHttpsRrQuery(endpoint = server.url("/dns-query").toString()),
                        fallbackQuery = null,
                    )

                assertArrayEquals(EchConfig, resolver.fetch("origin.example")?.echConfig)
            }
        }

    @Test
    fun aliasCycleSelfAndUnavailableDoNotFallBack() =
        runTest {
            for (responses in listOf(
                mapOf("origin.example" to "0 origin.example."),
                mapOf("origin.example" to "0 alias.example.", "alias.example" to "0 origin.example."),
                mapOf("origin.example" to "0 ."),
            )) {
                val resolver =
                    HttpsRrResolver(
                        query = encodedQuery(responses),
                        fallbackQuery = encodedQuery(mapOf("origin.example" to serviceConfig())),
                    )
                assertNull(resolver.fetch("origin.example"))
            }
        }

    @Test
    fun permitsThreeAliasHopsButNotFour() =
        runTest {
            for (hops in listOf(3, 4)) {
                val responses = mutableMapOf<String, String>()
                var host = "origin.example"
                repeat(hops) { index ->
                    val target = "alias$index.example"
                    responses[host] = "0 $target."
                    host = target
                }
                responses[host] = serviceConfig()
                val selected =
                    HttpsRrResolver(
                        query = encodedQuery(responses),
                        fallbackQuery = null,
                    ).fetch("origin.example")
                if (hops == 3) assertArrayEquals(EchConfig, selected?.echConfig) else assertNull(selected)
            }
        }

    private fun encodedQuery(responses: Map<String, String>): HttpsRrQuery =
        HttpsRrQuery { host ->
            val query = HttpsDnsWireBuilder.buildQuery(host)
            val response = dnsResponse(Message(query), responses.getValue(host))
            HttpsDnsWireParser.parseResponse(response, query)
        }

    private fun dnsResponse(
        query: Message,
        rdata: String,
    ): ByteArray =
        Message(query.header.id)
            .apply {
                header.setFlag(Flags.QR.toInt())
                addRecord(query.question, Section.QUESTION)
                addRecord(
                    Record.fromString(query.question.name, Type.HTTPS, DClass.IN, 60, rdata, Name.root),
                    Section.ANSWER,
                )
            }.toWire()

    private fun serviceConfig(): String = "1 . ech=${Base64.getEncoder().encodeToString(EchConfig)}"

    private companion object {
        val EchConfig =
            "003efe0d003a0000200020bb2f29e3e3057e0419d52fc5f44118776f8db61cea4fdf76079b93606c5a62480008000100010001000300076563682e636f6d0000"
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
    }
}
