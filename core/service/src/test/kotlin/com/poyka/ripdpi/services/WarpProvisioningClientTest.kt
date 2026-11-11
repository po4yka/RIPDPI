package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpProvisioningBindings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarpProvisioningClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun registerUsesExpectedHeadersAndMapsProvisioningResponse() =
        runTest {
            val bindings = RecordingProvisioningBindings(responseBody = sampleRegistrationResponse())

            val provisioningClient =
                DefaultWarpProvisioningClient(
                    nativeBindings = bindings,
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
            val request = bindings.lastRequest

            assertEquals("POST", request?.method)
            assertEquals(WarpRegistrationBaseUrl, request?.url)
            assertEquals(WarpProvisioningUserAgent, request?.headers?.get("User-Agent"))
            assertEquals(WarpProvisioningClientVersion, request?.headers?.get("CF-Client-Version"))
            assertNotNull(request?.body)
            assertTrue(request?.body.orEmpty().contains("\"warp_enabled\":true"))
            assertTrue(request?.body.orEmpty().contains("\"key\":\"base64-public-key\""))
            assertEquals("device-123", result.credentials.deviceId)
            assertEquals("sample-access-value", result.credentials.accessToken)
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
            val accessValue = fixtureAccessValue("refresh")
            val bindings = RecordingProvisioningBindings(responseBody = sampleRegistrationResponse())

            val provisioningClient =
                DefaultWarpProvisioningClient(
                    nativeBindings = bindings,
                    json = json,
                )
            provisioningClient.refresh(
                com.poyka.ripdpi.data
                    .WarpCredentials(deviceId = "device-123", accessToken = accessValue),
            )
            val request = bindings.lastRequest

            assertEquals("GET", request?.method)
            assertEquals("$WarpRegistrationBaseUrl/device-123", request?.url)
            assertEquals("Bearer $accessValue", request?.headers?.get(WarpAuthHeader))
        }

    @Test
    fun `refresh without a new token retains saved credentials`() =
        runTest {
            // The provider GET contract has a configuration but need not issue a new token.
            val response = sampleRegistrationResponse().replace("\"token\": \"sample-access-value\",", "")
            val saved =
                com.poyka.ripdpi.data.WarpCredentials(
                    deviceId = "device-123",
                    accessToken = fixtureAccessValue("saved"),
                    privateKey = "saved-private-key",
                    publicKey = "saved-public-key",
                )
            val client = DefaultWarpProvisioningClient(RecordingProvisioningBindings(responseBody = response), json)

            val refreshed = client.refresh(saved)

            assertEquals(saved.accessToken, refreshed.credentials.accessToken)
            assertEquals(saved.privateKey, refreshed.credentials.privateKey)
            assertEquals(saved.publicKey, refreshed.credentials.publicKey)
            assertEquals("162.159.192.1", refreshed.endpoint.ipv4)
        }

    @Test
    fun `refresh accepts a rotated response token`() =
        runTest {
            val saved =
                com.poyka.ripdpi.data.WarpCredentials(
                    deviceId = "device-123",
                    accessToken = fixtureAccessValue("saved"),
                )
            val client =
                DefaultWarpProvisioningClient(
                    RecordingProvisioningBindings(responseBody = sampleRegistrationResponse()),
                    json,
                )

            assertEquals("sample-access-value", client.refresh(saved).credentials.accessToken)
        }

    @Test
    fun `registration rejects missing and blank response tokens`() =
        runTest {
            val responses =
                listOf(
                    sampleRegistrationResponse().replace("\"token\": \"sample-access-value\",", ""),
                    sampleRegistrationResponse().replace("sample-access-value", " "),
                )
            responses.forEach { response ->
                val client = DefaultWarpProvisioningClient(RecordingProvisioningBindings(responseBody = response), json)

                val error =
                    runCatching {
                        client.register(
                            WarpRegisterDeviceRequest(publicKey = "public-key"),
                        )
                    }.exceptionOrNull()

                assertTrue(error is WarpProvisioningException.MalformedResponse)
            }
        }

    @Test
    fun `http errors do not expose provider bodies`() =
        runTest {
            val privateValue = fixtureAccessValue("private-error-body")
            val client =
                DefaultWarpProvisioningClient(
                    RecordingProvisioningBindings(statusCode = 403, responseBody = """{"token":"$privateValue"}"""),
                    json,
                )

            val error =
                runCatching { client.register(WarpRegisterDeviceRequest(publicKey = "public-key")) }.exceptionOrNull()

            assertTrue(error is WarpProvisioningException.AuthFailure)
            assertTrue(error?.message.orEmpty().contains("HTTP 403"))
            assertFalse(error?.stackTraceToString().orEmpty().contains(privateValue))
        }

    @Test
    fun `bridge errors do not expose native response details`() =
        runTest {
            val privateValue = fixtureAccessValue("private-bridge-error")
            val client =
                DefaultWarpProvisioningClient(
                    RecordingProvisioningBindings(responseBody = "", error = privateValue),
                    json,
                )

            val error =
                runCatching { client.register(WarpRegisterDeviceRequest(publicKey = "public-key")) }.exceptionOrNull()

            assertNotNull(error)
            assertFalse(error?.stackTraceToString().orEmpty().contains(privateValue))
        }

    @Test
    fun `malformed provider data does not expose payload through exception causes`() =
        runTest {
            val privateValue = fixtureAccessValue("private-malformed-body")
            val client =
                DefaultWarpProvisioningClient(
                    RecordingProvisioningBindings(responseBody = """{"token":"$privateValue","id":[]} invalid"""),
                    json,
                )

            val error =
                runCatching { client.register(WarpRegisterDeviceRequest(publicKey = "public-key")) }.exceptionOrNull()

            assertTrue(error is WarpProvisioningException.MalformedResponse)
            assertFalse(error?.stackTraceToString().orEmpty().contains(privateValue))
        }

    @Test
    fun `malformed bridge data does not expose payload through exception causes`() =
        runTest {
            val privateValue = fixtureAccessValue("private-malformed-bridge")
            val bindings =
                object : RipDpiWarpProvisioningBindings {
                    override fun executeProvisioning(requestJson: String): String =
                        """{"body":"$privateValue","statusCode":[]} invalid"""
                }
            val client = DefaultWarpProvisioningClient(bindings, json)

            val error =
                runCatching { client.register(WarpRegisterDeviceRequest(publicKey = "public-key")) }.exceptionOrNull()

            assertNotNull(error)
            assertFalse(error?.stackTraceToString().orEmpty().contains(privateValue))
        }

    @Test
    fun reservedBytesPadsShortOrInvalidClientIds() {
        assertArrayEquals(byteArrayOf(1, 2, 3), reservedBytesFromClientId("AQID"))
        assertArrayEquals(byteArrayOf(1, 2, 0), reservedBytesFromClientId("AQI="))
        assertArrayEquals(byteArrayOf(0, 0, 0), reservedBytesFromClientId("%%%"))
        assertArrayEquals(byteArrayOf(0, 0, 0), reservedBytesFromClientId(null))
    }

    @Test
    fun `refresh maps auth failures to provisioning auth exception`() =
        runTest {
            val accessValue = fixtureAccessValue("auth")
            val provisioningClient =
                DefaultWarpProvisioningClient(
                    nativeBindings =
                        RecordingProvisioningBindings(
                            statusCode = 403,
                            responseBody = """{"error":"forbidden"}""",
                        ),
                    json = json,
                )

            val error =
                runCatching {
                    provisioningClient.refresh(
                        com.poyka.ripdpi.data
                            .WarpCredentials(deviceId = "device-123", accessToken = accessValue),
                    )
                }.exceptionOrNull()

            assertEquals("AuthFailure", error?.javaClass?.simpleName)
            assertTrue(error?.message.orEmpty().contains("HTTP 403"))
        }

    @Test
    fun `register maps malformed responses to provisioning malformed exception`() =
        runTest {
            val provisioningClient =
                DefaultWarpProvisioningClient(
                    nativeBindings =
                        RecordingProvisioningBindings(
                            responseBody = """{"id":"device-123"}""",
                        ),
                    json = json,
                )

            val error =
                runCatching {
                    provisioningClient.register(
                        WarpRegisterDeviceRequest(
                            publicKey = "base64-public-key",
                            privateKey = "base64-private-key",
                        ),
                    )
                }.exceptionOrNull()

            assertEquals("MalformedResponse", error?.javaClass?.simpleName)
            assertTrue(error?.message.orEmpty().contains("malformed"))
        }

    private fun sampleRegistrationResponse(): String =
        """
        {
          "id": "device-123",
          "token": "sample-access-value",
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

    private fun fixtureAccessValue(suffix: String): String = listOf("access", "value", suffix).joinToString("-")

    @Serializable
    private data class NativeWarpProvisioningHttpRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String? = null,
    )

    @Serializable
    private data class NativeWarpProvisioningHttpResponse(
        val statusCode: Int? = null,
        val body: String? = null,
        val error: String? = null,
    )

    private class RecordingProvisioningBindings(
        private val statusCode: Int = 200,
        private val responseBody: String,
        private val error: String? = null,
    ) : RipDpiWarpProvisioningBindings {
        private val json = Json { ignoreUnknownKeys = true }

        var lastRequest: NativeWarpProvisioningHttpRequest? = null

        override fun executeProvisioning(requestJson: String): String? {
            lastRequest = json.decodeFromString(NativeWarpProvisioningHttpRequest.serializer(), requestJson)
            return json.encodeToString(
                NativeWarpProvisioningHttpResponse.serializer(),
                NativeWarpProvisioningHttpResponse(
                    statusCode = statusCode,
                    body = responseBody,
                    error = error,
                ),
            )
        }
    }
}
