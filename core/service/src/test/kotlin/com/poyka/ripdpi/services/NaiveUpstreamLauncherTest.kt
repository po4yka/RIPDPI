package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

internal fun unusedUpstreamLauncher(delegate: NaiveProxyLaunchDelegate): NaiveUpstreamLauncher =
    NaiveUpstreamLauncher(
        delegate,
        { _, _, _, _ -> error("upstream resolve is unused") },
        { error("upstream version probe is unused") },
        NaiveUpstreamConfigStore { error("upstream config store is unused") },
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NaiveUpstreamLauncherTest {
    private lateinit var baseDir: File
    private lateinit var delegate: RecordingDelegate
    private lateinit var resolver: RecordingResolver
    private var versionLine: String? = NaiveUpstreamVersionPrefix
    private val pin = "a".repeat(64)

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("naive-upstream-test").toFile().apply { deleteOnExit() }
        delegate = RecordingDelegate()
        resolver = RecordingResolver()
    }

    private fun launcher() =
        NaiveUpstreamLauncher(delegate, resolver, { versionLine }, NaiveUpstreamConfigStore { baseDir })

    private fun manager() = NaiveProxyManager(delegate, { error("Rust preflight must not run") }, launcher())

    private fun config(): ResolvedRipDpiRelayConfig =
        sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
            naiveUsername = "alice",
            naivePassword = "p@ss:w+rd", // Fixture credential.
            naiveExtraHeaders = "X-Token: tok-secret-1234",
            localSocksUsername = "local-user",
            localSocksPassword = "local-pass", // Fixture credential.
        )

    private fun configDir() = File(baseDir, "naive")

    private fun leftoverFiles(): List<File> = configDir().listFiles()?.toList().orEmpty()

    @Test
    fun `profile without a pin launches the upstream client with only the config path in argv`() =
        runTest {
            manager().start(config())

            val spec = requireNotNull(delegate.lastSpec)
            assertEquals("ripdpi-naive-chromium", spec.binaryName)
            assertEquals("/native-lib/libripdpi-naive-chromium.so", delegate.lastBinary?.absolutePath)
            assertEquals(1, spec.commandArguments.size)
            assertTrue(spec.commandArguments.single().startsWith(configDir().absolutePath))
            assertNull(spec.standardInput)
            val secrets = listOf("alice", "p@ss:w+rd", "tok-secret-1234", "local-user", "local-pass")
            secrets.forEach { secret -> assertFalse(secret, spec.commandArguments.any { secret in it }) }
            assertTrue(spec.redactedValues.containsAll(secrets))
            assertTrue("p%40ss%3Aw%2Brd" in spec.redactedValues)
            assertEquals(listOf("--version"), spec.versionArguments)
        }

    @Test
    fun `profile with a pin keeps the exact Rust helper arguments`() =
        runTest {
            val preflight =
                NaiveProxyPreflightProbe {
                    NaiveProxyPreflightResult.Probed(NaiveProxyProbe(1, "0.1.0", listOf("local-listener-auth")))
                }
            NaiveProxyManager(delegate, preflight, launcher()).start(config().copy(naiveServerPinSha256 = pin))

            val spec = requireNotNull(delegate.lastSpec)
            assertEquals("ripdpi-naiveproxy", spec.binaryName)
            assertEquals(
                listOf(
                    "--listen",
                    "127.0.0.1:1080",
                    "--server",
                    "relay.example",
                    "--server-port",
                    "443",
                    "--server-name",
                    "relay.example",
                    "--credentials-stdin",
                    "--server-pin-sha256",
                    pin,
                ),
                spec.commandArguments,
            )
            assertNotNull(spec.standardInput)
            assertTrue(resolver.calls.isEmpty())
            assertFalse(configDir().exists())
        }

    @Test
    fun `config json carries encoded credentials, headers and the first resolved address`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("203.0.113.7", "203.0.113.8"))

            manager().start(config())

            val json = JSONObject(requireNotNull(delegate.configAtLaunch))
            assertEquals("socks://local-user:local-pass@127.0.0.1:1080", json.getString("listen"))
            assertEquals("https://alice:p%40ss%3Aw%2Brd@relay.example:443", json.getString("proxy"))
            assertEquals("X-Token: tok-secret-1234", json.getString("extra-headers"))
            assertEquals("MAP relay.example 203.0.113.7", json.getString("host-resolver-rules"))
            assertEquals("", json.getString("log"))
            assertEquals(listOf("ripdpi-naiveproxy:relay.example:443"), resolver.calls)
        }

    @Test
    fun `ipv6 mapping is bracketed and an empty password keeps the colon`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("2001:db8::7"))

            manager().start(config().copy(naivePassword = "", naiveExtraHeaders = ""))

            val json = JSONObject(requireNotNull(delegate.configAtLaunch))
            assertEquals("https://alice:@relay.example:443", json.getString("proxy"))
            assertEquals("MAP relay.example [2001:db8::7]", json.getString("host-resolver-rules"))
            assertFalse(json.has("extra-headers"))
        }

    @Test
    fun `profile without credentials omits the userinfo`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("203.0.113.7"))

            manager().start(config().copy(naiveUsername = null, naivePassword = null))

            val json = JSONObject(requireNotNull(delegate.configAtLaunch))
            assertEquals("https://relay.example:443", json.getString("proxy"))
        }

    @Test
    fun `ip literal server skips the resolve and writes no host mapping`() =
        runTest {
            manager().start(config().copy(server = "2001:db8::1", serverName = ""))

            val json = JSONObject(requireNotNull(delegate.configAtLaunch))
            assertEquals("https://alice:p%40ss%3Aw%2Brd@[2001:db8::1]:443", json.getString("proxy"))
            assertFalse(json.has("host-resolver-rules"))
            assertTrue(resolver.calls.isEmpty())
        }

    @Test
    fun `server name differing from an ip server is mapped to that ip without a resolve`() =
        runTest {
            manager().start(config().copy(server = "192.0.2.9", serverName = "front.example"))

            val json = JSONObject(requireNotNull(delegate.configAtLaunch))
            assertEquals("MAP front.example 192.0.2.9", json.getString("host-resolver-rules"))
            assertTrue(json.getString("proxy").endsWith("@front.example:443"))
            assertTrue(resolver.calls.isEmpty())
        }

    @Test
    fun `config file is owner only while launching and gone after readiness`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("203.0.113.7"))

            manager().start(config())

            val path = requireNotNull(delegate.configPathAtLaunch)
            assertEquals("rw-------", delegate.permissionsAtLaunch)
            assertEquals("rwx------", delegate.dirPermissionsAtLaunch)
            assertFalse(File(path).exists())
            assertTrue(leftoverFiles().isEmpty())
        }

    @Test
    fun `config file is deleted when the launch fails`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("203.0.113.7"))
            delegate.startFailure = IllegalStateException("Subprocess relay exited before readiness")

            val error = runCatching { manager().start(config()) }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(leftoverFiles().isEmpty())
            assertFalse(error?.message.orEmpty().contains("p@ss"))
        }

    @Test
    fun `stop removes a config file that is still on disk and is idempotent`() =
        runTest {
            val store = NaiveUpstreamConfigStore { baseDir }
            val file = store.write("{}")
            assertTrue(file.exists())

            store.delete(file)
            store.delete(file)
            store.delete(null)

            assertFalse(file.exists())
            val launcher = launcher()
            launcher.cleanup()
            launcher.cleanup()
            manager().stop()
            assertEquals(1, delegate.stops)
        }

    @Test
    fun `protected dns failures reject the start as dns and launch nothing`() =
        runTest {
            val failures =
                listOf(
                    "resolver exited with code 2",
                    "resolver timed out",
                    "resolver returned no address",
                )
            for (message in failures) {
                resolver.result = NaiveProxyResolveResult.Failed(message)
                delegate.failureClass = null

                val error = runCatching { manager().start(config()) }.exceptionOrNull()

                assertTrue(message, error is NaiveProxyCompatibilityException)
                assertEquals("dns", delegate.failureClass)
                assertTrue(error?.message.orEmpty().contains(message))
            }
            assertEquals(0, delegate.starts)
            assertFalse(configDir().exists())
        }

    @Test
    fun `a version other than the pinned release is rejected before launch`() =
        runTest {
            val pinned = NaiveUpstreamVersionPrefix
            for (line in listOf(null, "naive 153.0.1.1", "${pinned}1", "$pinned.1", "garbage")) {
                versionLine = line
                delegate.failureClass = null

                val error = runCatching { manager().start(config()) }.exceptionOrNull()

                assertTrue(line.toString(), error is NaiveProxyCompatibilityException)
                assertEquals("relay_compatibility", delegate.failureClass)
            }
            versionLine = "$NaiveUpstreamVersionPrefix (extra)"
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("203.0.113.7"))
            manager().start(config())
            assertEquals(1, delegate.starts)
        }

    @Test
    fun `outbound bind ip is refused for the upstream client`() =
        runTest {
            val error = runCatching { manager().start(config().copy(outboundBindIp = "192.0.2.10")) }.exceptionOrNull()

            assertTrue(error is NaiveProxyCompatibilityException)
            assertEquals("config", delegate.failureClass)
            assertEquals(0, delegate.starts)
        }

    @Test
    fun `upstream spec needs no protect path under vpn protection`() {
        val path =
            resolveSubprocessProtectPath(
                config = config().copy(socketProtection = RelaySocketProtection.VpnRequired),
                spec =
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = NaiveUpstreamBinaryName,
                        commandArguments = emptyList(),
                        runtimeKind = RelayKindNaiveProxy,
                        protectionCapability = SubprocessSocketProtectionCapability.OwnUidExcluded,
                    ),
                activeProtectPath = "/tmp/protect.sock",
            )

        assertNull(path)
    }

    @Test
    fun `percent encoding escapes userinfo delimiters and multibyte text`() {
        assertEquals("a%40b%3Ac%2Bd%20e", percentEncodeUserInfo("a@b:c+d e"))
        assertEquals("Az09-._~", percentEncodeUserInfo("Az09-._~"))
        assertEquals("%C3%A9", percentEncodeUserInfo("é"))
    }

    // DefaultNaiveProxyResolveClient against real scripts.

    private fun script(body: String): NaiveProxyBinaryRef =
        File
            .createTempFile("naive-resolve-", ".sh")
            .apply {
                writeText("#!/bin/sh\n$body\n")
                check(setExecutable(true))
                deleteOnExit()
            }.let { NaiveProxyBinaryRef(it.absolutePath) }

    private fun client(
        timeoutMillis: Long = 5_000L,
        protectPath: String? = null,
    ) = DefaultNaiveProxyResolveClient(Dispatchers.IO, { protectPath }, timeoutMillis)

    @Test
    fun `resolve client parses addresses and passes the protect path and arguments`() =
        runTest {
            val out = File.createTempFile("naive-resolve-args-", ".txt").apply { deleteOnExit() }
            val binary =
                script(
                    "printf '%s|%s' \"\$*\" \"\$RIPDPI_PROTECT_PATH\" > '${out.absolutePath}'; " +
                        "echo 'RIPDPI-RESOLVED|192.0.2.1,192.0.2.2'",
                )

            val result =
                client(protectPath = "/data/protect.sock")
                    .resolve(binary, "relay.example", 443, RelaySocketProtection.VpnRequired)

            assertEquals(NaiveProxyResolveResult.Resolved(listOf("192.0.2.1", "192.0.2.2")), result)
            assertEquals("--resolve relay.example --server-port 443|/data/protect.sock", out.readText())
        }

    @Test
    fun `resolve client fails on non zero exit, empty result, timeout and missing protect path`() =
        runTest {
            val protection = RelaySocketProtection.Inactive
            val nonZero = script("echo 'RIPDPI-ERROR|naiveproxy|dns|no answer'; exit 3")
            assertEquals(
                NaiveProxyResolveResult.Failed("no answer"),
                client().resolve(nonZero, "relay.example", 443, protection),
            )
            val empty = script("echo 'RIPDPI-RESOLVED|'")
            assertTrue(client().resolve(empty, "relay.example", 443, protection) is NaiveProxyResolveResult.Failed)
            val silent = script("exit 0")
            assertTrue(client().resolve(silent, "relay.example", 443, protection) is NaiveProxyResolveResult.Failed)
            val slow = script("exec sleep 30")
            assertEquals(
                NaiveProxyResolveResult.Failed("resolver timed out"),
                client(timeoutMillis = 300L).resolve(slow, "relay.example", 443, protection),
            )
            val ok = script("echo 'RIPDPI-RESOLVED|192.0.2.1'")
            assertTrue(
                client(protectPath = null).resolve(ok, "relay.example", 443, RelaySocketProtection.VpnRequired) is
                    NaiveProxyResolveResult.Failed,
            )
        }

    @Test
    fun `lf separated extra headers are written as crlf`() =
        runTest {
            manager().start(config().copy(naiveExtraHeaders = "X-One: 1\nX-Two: 2"))

            val json = JSONObject(requireNotNull(delegate.configAtLaunch))
            assertEquals("X-One: 1\r\nX-Two: 2", json.getString("extra-headers"))
        }

    @Test
    fun `reserved extra header names are rejected as config before launch`() =
        runTest {
            for (raw in listOf("Proxy-Authorization: Basic abc", "Host: evil.example")) {
                delegate.failureClass = null

                val error = runCatching { manager().start(config().copy(naiveExtraHeaders = raw)) }.exceptionOrNull()

                assertTrue(raw, error is NaiveProxyCompatibilityException)
                assertEquals("config", delegate.failureClass)
            }
            assertEquals(0, delegate.starts)
            assertFalse(configDir().exists())
        }

    @Test
    fun `missing local listener credentials are rejected as config`() =
        runTest {
            val error =
                runCatching {
                    manager().start(config().copy(localSocksUsername = null, localSocksPassword = null))
                }.exceptionOrNull()

            assertTrue(error is NaiveProxyCompatibilityException)
            assertEquals("config", delegate.failureClass)
            assertEquals(0, delegate.starts)
        }

    @Test
    fun `proxy hosts that could inject config text are rejected as config`() =
        runTest {
            val hosts = listOf("a b.example", "a,b.example", "a;b", "*.example", "a/b", "u@host", "a\nb", "a\u0000b")
            for (host in hosts) {
                val candidates =
                    listOf(config().copy(server = host, serverName = ""), config().copy(serverName = host))
                for (candidate in candidates) {
                    delegate.failureClass = null

                    val error = runCatching { manager().start(candidate) }.exceptionOrNull()

                    assertTrue(host, error is NaiveProxyCompatibilityException)
                    assertEquals("config", delegate.failureClass)
                }
            }
            assertEquals(0, delegate.starts)
        }

    @Test
    fun `resolved addresses are validated and ipv4 is preferred`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("garbage;x", "2001:db8::7", "203.0.113.9"))

            manager().start(config())

            val json = JSONObject(requireNotNull(delegate.configAtLaunch))
            assertEquals("MAP relay.example 203.0.113.9", json.getString("host-resolver-rules"))
        }

    @Test
    fun `an ipv6 address is used when no ipv4 address is valid`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("bad host", "2001:db8::7", "2001:db8::8"))

            manager().start(config())

            val json = JSONObject(requireNotNull(delegate.configAtLaunch))
            assertEquals("MAP relay.example [2001:db8::7]", json.getString("host-resolver-rules"))
        }

    @Test
    fun `an all garbage resolve result is rejected as dns`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("evil;rule", "x y"))

            val error = runCatching { manager().start(config()) }.exceptionOrNull()

            assertTrue(error is NaiveProxyCompatibilityException)
            assertEquals("dns", delegate.failureClass)
            assertEquals(0, delegate.starts)
        }

    @Test
    fun `cleanup of one write leaves an overlapping write in place`() {
        val store = NaiveUpstreamConfigStore { baseDir }
        val first = store.write("{}")
        val second = store.write("{}")

        store.delete(first)

        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun `an overlapping start keeps its config file when another start finishes or stops`() =
        runTest {
            resolver.result = NaiveProxyResolveResult.Resolved(listOf("203.0.113.7"))
            var other: File? = null
            val store = NaiveUpstreamConfigStore { baseDir }
            delegate.onStart = { other = store.write("{}") }
            val launcher = NaiveUpstreamLauncher(delegate, resolver, { versionLine }, store)

            launcher.start(config())
            launcher.cleanup()

            assertTrue(requireNotNull(other).exists())
        }

    @Test
    fun `stale files are swept once at the first write of a process`() {
        val dir = configDir().apply { mkdirs() }
        val stale = File(dir, "stale.json").apply { writeText("{}") }
        val store = NaiveUpstreamConfigStore { baseDir }

        val first = store.write("{}")
        val late = File(dir, "late.json").apply { writeText("{}") }
        val second = store.write("{}")

        assertFalse(stale.exists())
        assertTrue(first.exists() && second.exists() && late.exists())
    }

    @Test
    fun `redactions cover base64 Basic and lowercase percent-encoded credentials`() {
        val fixtureProxyPassword = "p@ss/w:rd"
        val values =
            NaiveUpstreamConfigValues(
                proxyHost = "naive.test",
                proxyPort = 8443,
                proxyUsername = "alice",
                proxyPassword = fixtureProxyPassword,
                localUsername = "lu",
                localPassword = "lp",
                localHost = "127.0.0.1",
                localPort = 1080,
                extraHeaders = "",
                mappedAddress = null,
            )
        val basic = Base64.getEncoder().encodeToString("alice:p@ss/w:rd".toByteArray())
        val redactions = naiveUpstreamRedactions(values)
        val spec =
            SubprocessSocksRelayLaunchSpec(
                binaryName = NaiveUpstreamBinaryName,
                commandArguments = emptyList(),
                runtimeKind = RelayKindNaiveProxy,
                redactedValues = redactions,
            )

        assertTrue(basic in redactions)
        assertTrue("p%40ss%2fw%3ard" in redactions)
        assertTrue("p%40ss%2Fw%3Ard" in redactions)
        val line = "[1030/1.2:ERROR:net/tools/naive/x.cc:1] Proxy-Authorization: Basic $basic p%40ss%2fw%3ard"
        val event = SubprocessRelayOutputParser().parse(line, spec) as SubprocessRelayOutputEvent.PlainError
        assertFalse(event.message.contains(basic))
        assertEquals("upstream client error (x.cc)", event.message)
        // A non-fixed-shape surface that still emits text must be redacted: the redaction runs before parsing.
        val helperLine = "PROXY-ERROR $basic"
        val helper = SubprocessRelayOutputParser().parse(helperLine, spec.copy(binaryName = "ripdpi-naiveproxy"))
        assertEquals(SubprocessRelayOutputEvent.PlainError("PROXY-ERROR <redacted>"), helper)
    }

    @Test
    fun `resolve client does not stall on output larger than the pipe buffer`() =
        runTest {
            val binary =
                script("yes xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx | head -n 20000; echo 'RIPDPI-RESOLVED|192.0.2.1'")

            val result =
                client(timeoutMillis = 4_000L).resolve(binary, "relay.example", 443, RelaySocketProtection.Inactive)

            assertEquals(NaiveProxyResolveResult.Resolved(listOf("192.0.2.1")), result)
        }

    private class RecordingResolver : NaiveProxyResolveClient {
        var result: NaiveProxyResolveResult = NaiveProxyResolveResult.Resolved(listOf("203.0.113.7"))
        val calls = mutableListOf<String>()

        override suspend fun resolve(
            binary: NaiveProxyBinaryRef,
            host: String,
            port: Int,
            protection: RelaySocketProtection,
        ): NaiveProxyResolveResult {
            calls += "${File(binary.absolutePath).name.removePrefix("lib").removeSuffix(".so")}:$host:$port"
            return result
        }
    }

    private class RecordingDelegate : NaiveProxyLaunchDelegate {
        var starts = 0
        var stops = 0
        var lastSpec: SubprocessSocksRelayLaunchSpec? = null
        var lastBinary: NaiveProxyBinaryRef? = null
        var failureClass: String? = null
        var startFailure: Exception? = null
        var configAtLaunch: String? = null
        var configPathAtLaunch: String? = null
        var permissionsAtLaunch: String? = null
        var dirPermissionsAtLaunch: String? = null
        var onStart: (() -> Unit)? = null

        override fun resolveBinary(binaryName: String) = NaiveProxyBinaryRef("/native-lib/lib$binaryName.so")

        override suspend fun start(
            binary: NaiveProxyBinaryRef,
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
        ) {
            starts += 1
            onStart?.invoke()
            lastSpec = spec
            lastBinary = binary
            if (spec.binaryName == NaiveUpstreamBinaryName) {
                val file = File(spec.commandArguments.single())
                configPathAtLaunch = file.absolutePath
                configAtLaunch = file.readText()
                permissionsAtLaunch = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))
                dirPermissionsAtLaunch =
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parentFile.toPath()))
            }
            startFailure?.let { throw it }
        }

        override suspend fun waitForExit(): Int = 0

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot = NativeRuntimeSnapshot(source = "relay")

        override fun noteRestarting(reason: String) = Unit

        override fun notePrelaunchFailure(
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
            failureClass: String,
            message: String,
        ) {
            this.failureClass = failureClass
        }

        override suspend fun stop() {
            stops += 1
        }
    }
}
