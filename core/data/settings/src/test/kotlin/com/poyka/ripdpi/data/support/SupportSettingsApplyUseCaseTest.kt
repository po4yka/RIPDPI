package com.poyka.ripdpi.data.support

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SupportSettingsApplyUseCaseTest {
    @Test
    fun `package codec rejects unsupported schema`() {
        val result =
            SupportSettingsPackageCodec.decode(
                """{"schema":2,"operations":[{"op":"set","path":"settings.app_theme","value":"dark"}]}""",
            )

        assertEquals(SupportSettingsPackageDecodeResult.Error.UnsupportedSchema, result)
    }

    @Test
    fun `preview reports primitive changes without mutating settings`() {
        val useCase = SupportSettingsApplyUseCase(FakeAppSettingsRepository())
        val pkg =
            SupportSettingsPackage(
                title = "Support fix",
                operations =
                    listOf(
                        SupportSettingsOperation("set", "settings.app_theme", JsonPrimitive("dark")),
                        SupportSettingsOperation("set", "settings.proxy_port", JsonPrimitive(1080)),
                        SupportSettingsOperation("set", "settings.tcp_fast_open", JsonPrimitive(true)),
                    ),
            )

        val result = useCase.preview(AppSettings.getDefaultInstance(), pkg)

        val preview = (result as SupportSettingsPreviewResult.Ready).preview
        assertEquals("Support fix", preview.title)
        assertEquals(
            listOf(
                "settings.app_theme",
                "settings.proxy_port",
                "settings.tcp_fast_open",
            ),
            preview.changes.map {
                it.path
            },
        )
        assertFalse(preview.hasSensitiveChanges)
    }

    @Test
    fun `apply stages all operations before replacing repository settings`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val useCase = SupportSettingsApplyUseCase(repository)
            val packageJson =
                """
                {
                  "schema": 1,
                  "operations": [
                    {"op": "set", "path": "settings.app_theme", "value": "dark"},
                    {"op": "set", "path": "settings.proxy_port", "value": 1080},
                    {"op": "set", "path": "settings.tcp_fast_open", "value": true}
                  ]
                }
                """.trimIndent()

            val result = useCase.apply(packageJson)

            assertTrue(result is SupportSettingsApplyResult.Success)
            assertEquals("dark", repository.snapshot().appTheme)
            assertEquals(1080, repository.snapshot().proxyPort)
            assertTrue(repository.snapshot().tcpFastOpen)
        }

    @Test
    fun `invalid operation aborts without replacing settings`() =
        runTest {
            val initial = AppSettings.newBuilder().setAppTheme("light").build()
            val repository = FakeAppSettingsRepository(initial)
            val useCase = SupportSettingsApplyUseCase(repository)
            val packageJson =
                """
                {
                  "schema": 1,
                  "operations": [
                    {"op": "set", "path": "settings.app_theme", "value": "dark"},
                    {"op": "set", "path": "settings.not_real", "value": true}
                  ]
                }
                """.trimIndent()

            val result = useCase.apply(packageJson)

            assertTrue(result is SupportSettingsApplyResult.Invalid)
            assertEquals("light", repository.snapshot().appTheme)
        }

    @Test
    fun `apply preserves changes committed before its atomic update`() =
        runTest {
            val repository =
                FakeAppSettingsRepository().apply {
                    beforeWrite = { setProxyPort(8080).setAppTheme("light") }
                }
            val result =
                SupportSettingsApplyUseCase(repository).apply(
                    """{"schema":1,"operations":[{"op":"set","path":"settings.app_theme","value":"dark"}]}""",
                ) as SupportSettingsApplyResult.Success

            assertEquals(8080, repository.snapshot().proxyPort)
            assertEquals("light", result.changes.single().previous)
            assertEquals("dark", repository.snapshot().appTheme)
        }

    @Test
    fun `malformed embedded activation filter is invalid without writing`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val useCase = SupportSettingsApplyUseCase(repository)
            val packageJson =
                """{"schema":1,"operations":[{"op":"set","path":"settings.group_activation_filter","value":"_w"}]}"""

            assertTrue(useCase.preview(packageJson) is SupportSettingsPreviewResult.Invalid)
            assertTrue(useCase.apply(packageJson) is SupportSettingsApplyResult.Invalid)
            assertEquals(AppSettings.getDefaultInstance(), repository.snapshot())
        }

    @Test
    fun `repeated strings and message values are supported`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val useCase = SupportSettingsApplyUseCase(repository)
            val tcpStep =
                StrategyTcpStep
                    .newBuilder()
                    .setKind("split")
                    .setFragmentCount(2)
                    .build()
            val packageJson =
                buildJsonObject {
                    put("schema", 1)
                    put(
                        "operations",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("op", "set")
                                    put("path", "settings.encrypted_dns_bootstrap_ips")
                                    put("value", buildJsonArray { add(JsonPrimitive("8.8.8.8")) })
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("op", "set")
                                    put("path", "settings.tcp_chain_steps")
                                    put("value", buildJsonArray { add(JsonPrimitive(base64(tcpStep.toByteArray()))) })
                                },
                            )
                        },
                    )
                }.toString()

            val result = useCase.apply(packageJson)

            assertTrue(result is SupportSettingsApplyResult.Success)
            assertEquals(listOf("8.8.8.8"), repository.snapshot().encryptedDnsBootstrapIpsList)
            assertEquals(
                "split",
                repository
                    .snapshot()
                    .tcpChainStepsList
                    .single()
                    .kind,
            )
        }

    @Test
    fun `registry exposes every generated app settings builder field`() {
        val expected =
            AppSettings.Builder::class.java.methods
                .filter { it.returnType == AppSettings.Builder::class.java }
                .filter { it.name.startsWith("set") || it.name.startsWith("addAll") }
                .filterNot { it.name in setOf("setUnknownFields", "setField", "setRepeatedField") }
                .mapTo(sortedSetOf()) { method ->
                    val suffix =
                        if (method.name.startsWith("addAll")) {
                            method.name.removePrefix("addAll")
                        } else {
                            method.name.removePrefix("set")
                        }
                    "settings.${suffix.toSnakeCaseForTest()}"
                }

        assertTrue(expected.size > 150)
        assertEquals(expected, SupportSettingsFieldRegistry.supportedPaths.toSortedSet())
    }

    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private class FakeAppSettingsRepository(
    initial: AppSettings = AppSettings.getDefaultInstance(),
) : AppSettingsRepository {
    private val state = MutableStateFlow(initial)
    var beforeWrite: AppSettings.Builder.() -> Unit = {}

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(beforeWrite)
                .build()
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value =
            state.value
                .toBuilder()
                .apply(beforeWrite)
                .build()
        state.value = settings
    }
}

private fun String.toSnakeCaseForTest(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .replace('-', '_')
        .lowercase()
