package com.poyka.ripdpi.core.detection.community

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommunityComparisonClientTest {
    @Test
    fun `fetchStats closes response body and decodes payload`() =
        runTest {
            val responseBody =
                TrackingResponseBody(
                    """
                    {
                      "totalReports": 7,
                      "verdictDistribution": {
                        "NOT_DETECTED": 4
                      },
                      "averageStealthScore": 82.5
                    }
                    """.trimIndent()
                        .toResponseBody("application/json".toMediaType()),
                )
            val client =
                CommunityComparisonClient(
                    fakeHttpClient { request ->
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(responseBody)
                            .build()
                    },
                    testDispatchers(),
                )

            val result = client.fetchStats("https://example.test/stats.json")

            assertTrue(responseBody.closed)
            assertTrue(result.isSuccess)
            assertEquals(7, result.getOrThrow().totalReports)
        }

    @Test
    fun `fetchStats fails on blank response body`() =
        runTest {
            val client =
                CommunityComparisonClient(
                    fakeHttpClient { request ->
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("   ".toResponseBody("application/json".toMediaType()))
                            .build()
                    },
                    testDispatchers(),
                )

            val result = client.fetchStats("https://example.test/stats.json")

            assertTrue(result.isFailure)
            assertEquals(
                "Community stats response body was empty",
                result.exceptionOrNull()?.message,
            )
        }

    private fun fakeHttpClient(responseBuilder: (Request) -> Response): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain -> responseBuilder(chain.request()) }
            .build()

    private fun testDispatchers(): AppCoroutineDispatchers {
        val dispatcher = UnconfinedTestDispatcher()
        return AppCoroutineDispatchers(
            default = dispatcher,
            io = dispatcher,
            main = dispatcher,
        )
    }

    private class TrackingResponseBody(
        private val delegate: ResponseBody,
    ) : ResponseBody() {
        var closed: Boolean = false
            private set

        override fun contentLength(): Long = delegate.contentLength()

        override fun contentType() = delegate.contentType()

        override fun source() = delegate.source()

        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
