package com.poyka.ripdpi.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarpProvisioningClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun registerUsesExpectedHeadersAndMapsProvisioningResponse() =
        runTest {
            var capturedMethod: String? = null
            var capturedUrl: String? = null
            var capturedUserAgent: String? = null
            var capturedClientVersion: String? = null
            var capturedBody: String? = null
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedMethod = request.method
                        capturedUrl = request.url.toString()
                        capturedUserAgent = request.header("User-Agent")
                        capturedClientVersion = request.header("CF-Client-Version")
                        capturedBody = request.bodyAsString()
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(sampleRegistrationResponse().toResponseBody())
                            .build()
                    }.build()

            val provisioningClient =
                DefaultWarpProvisioningClient(
                    tlsClientFactory = TestOwnedTlsClientFactory(client),
                    json = json,
                )
            val result =
                provisioningClient.register(
                    WarpRegisterDeviceRequest(
                        publicKey = "base64-public-key",
                        privateKey = "base64-private-key",
                        tos = "2026-04-05T12:00:00.000Z",
                    ),
                )

            assertEquals("POST", capturedMethod)
            assertEquals(WarpRegistrationBaseUrl, capturedUrl)
            assertEquals(WarpProvisioningUserAgent, capturedUserAgent)
            assertEquals(WarpProvisioningClientVersion, capturedClientVersion)
            assertNotNull(capturedBody)
            assertTrue(capturedBody.orEmpty().contains("\"warp_enabled\":true"))
            assertTrue(capturedBody.orEmpty().contains("\"key\":\"base64-public-key\""))
            assertEquals("device-123", result.credentials.deviceId)
            assertEquals("token-123", result.credentials.accessToken)
            assertEquals("base64-private-key", result.credentials.privateKey)
            assertEquals("base64-public-key", result.credentials.publicKey)
            assertEquals("free", result.accountType)
            assertEquals("engage.cloudflareclient.com", result.endpoint.host)
            assertEquals(2408, result.endpoint.port)
            assertArrayEquals(byteArrayOf(1, 2, 3), result.reservedBytes)
        }

    @Test
    fun refreshUsesBearerAuthorizationAndDevicePath() =
        runTest {
            var capturedMethod: String? = null
            var capturedUrl: String? = null
            var capturedAuth: String? = null
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedMethod = request.method
                        capturedUrl = request.url.toString()
                        capturedAuth = request.header(WarpAuthHeader)
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(sampleRegistrationResponse().toResponseBody())
                            .build()
                    }.build()

            val provisioningClient =
                DefaultWarpProvisioningClient(
                    tlsClientFactory = TestOwnedTlsClientFactory(client),
                    json = json,
                )
            provisioningClient.refresh(
                com.poyka.ripdpi.data.WarpCredentials(
                    deviceId = "device-123",
                    accessToken = "token-123",
                ),
            )

            assertEquals("GET", capturedMethod)
            assertEquals("$WarpRegistrationBaseUrl/device-123", capturedUrl)
            assertEquals("Bearer token-123", capturedAuth)
        }

    @Test
    fun reservedBytesPadsShortOrInvalidClientIds() {
        assertArrayEquals(byteArrayOf(1, 2, 3), reservedBytesFromClientId("AQID"))
        assertArrayEquals(byteArrayOf(1, 2, 0), reservedBytesFromClientId("AQI="))
        assertArrayEquals(byteArrayOf(0, 0, 0), reservedBytesFromClientId("%%%"))
        assertArrayEquals(byteArrayOf(0, 0, 0), reservedBytesFromClientId(null))
    }

    private fun okhttp3.Request.bodyAsString(): String? {
        val body = body ?: return null
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun sampleRegistrationResponse(): String =
        """
        {
          "id": "device-123",
          "token": "token-123",
          "account": {
            "id": "account-123",
            "account_type": "free",
            "warp_plus": false,
            "premium_data": 0,
            "quota": 0,
            "license": "license-123"
          },
          "config": {
            "client_id": "AQID",
            "interface": {
              "addresses": {
                "v4": "172.16.0.2/32",
                "v6": "2606:4700:110:8a36::2/128"
              }
            },
            "peers": [
              {
                "public_key": "peer-public-key",
                "endpoint": {
                  "host": "engage.cloudflareclient.com:2408",
                  "v4": "162.159.192.1",
                  "v6": "2606:4700:d0::a29f:c001"
                }
              }
            ]
          }
        }
        """.trimIndent()

    private class TestOwnedTlsClientFactory(
        private val client: OkHttpClient,
    ) : OwnedTlsClientFactory {
        override fun currentProfile(): String = "native_default"

        override fun create(
            forcedTlsVersions: List<okhttp3.TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient = client
    }
}
