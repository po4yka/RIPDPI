package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipeStatClientTest {
    @Test
    fun parsesAnnouncedPrefixesFromJson() =
        runTest {
            val client =
                RipeStatClient(
                    httpClient =
                        responseClient(
                            200,
                            """
                            {
                              "data": {
                                "prefixes": [
                                  {"prefix": "203.0.113.0/24"},
                                  {"prefix": "198.51.100.0/24"}
                                ]
                              }
                            }
                            """.trimIndent(),
                        ),
                )

            assertEquals(
                listOf("203.0.113.0/24", "198.51.100.0/24"),
                client.announcedPrefixes(13238),
            )
        }

    @Test
    fun rateLimitRetriesOnceThenSurfacesContext() =
        runTest {
            var calls = 0
            val client =
                RipeStatClient(
                    httpClient =
                        responseClient { request ->
                            calls += 1
                            val code = if (calls == 1) 429 else 429
                            Response
                                .Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(code)
                                .message("rate limited")
                                .body("slow down".toResponseBody())
                                .build()
                        },
                    retryDelayMs = 0,
                )

            val error = runCatching { client.announcedPrefixes(13238) }.exceptionOrNull()

            assertEquals(2, calls)
            assertTrue(error?.message.orEmpty().contains("RIPE Stat rate limited for AS13238"))
        }

    private fun responseClient(
        code: Int,
        body: String,
    ): OkHttpClient =
        responseClient { request ->
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }

    private fun responseClient(responder: (okhttp3.Request) -> Response): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(Interceptor { chain -> responder(chain.request()) })
            .build()
}
