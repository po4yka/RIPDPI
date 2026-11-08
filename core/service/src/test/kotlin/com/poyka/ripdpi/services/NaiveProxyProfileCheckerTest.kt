package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class NaiveProxyProfileCheckerTest {
    private val dir = Files.createTempDirectory("naive-check").toFile().apply { deleteOnExit() }
    private val argvFile = File(dir, "argv")
    private val stdinFile = File(dir, "stdin")
    private val protectPathFile = File(dir, "protect-path")
    private val config =
        sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
            naiveUsername = "alice",
            naivePassword = "s3cret-pass", // Fixture credential.
        )

    /** A stand-in helper that records argv and stdin, then runs [body]. */
    private fun helper(body: String): File =
        File(dir, "helper").apply {
            writeText(
                "#!/bin/sh\necho \"$@\" > '$argvFile'\ncat > '$stdinFile'\n" +
                    "printf '%s' \"\${RIPDPI_PROTECT_PATH-}\" > '$protectPathFile'\n$body\n",
            )
            setExecutable(true)
        }

    private fun checker(
        body: String,
        timeoutMillis: Long = 5_000,
    ) = NaiveProxyProfileChecker(
        prepare = { _, _ -> config },
        binary = { helper(body) },
        ioDispatcher = Dispatchers.IO,
        timeoutMillis = timeoutMillis,
    )

    private val profile = RelayProfileRecord(id = "draft", kind = RelayKindNaiveProxy)
    private val credentials = RelayCredentialRecord(profileId = "draft")

    @Test
    fun `VPN check receives the captured endpoint in the helper environment`() =
        runTest {
            val checker =
                NaiveProxyProfileChecker(
                    prepare = { _, _ -> config.copy(socketProtection = RelaySocketProtection.VpnRequired) },
                    binary = { helper("echo 'RIPDPI-CHECK|naiveproxy|ok'") },
                    ioDispatcher = Dispatchers.IO,
                    protectPath = { "/tmp/vpn-profile-protect.sock" },
                )

            assertEquals(NaiveProxyCheckOutcome.Ok, checker.check(profile, credentials))
            assertEquals("/tmp/vpn-profile-protect.sock", protectPathFile.readText())
        }

    @Test
    fun `VPN check rejects a result after its protection owner changes`() =
        runTest {
            val release = File(dir, "release")
            val currentPath = AtomicReference("/tmp/vpn-profile-old.sock")
            val checker =
                NaiveProxyProfileChecker(
                    prepare = { _, _ -> config.copy(socketProtection = RelaySocketProtection.VpnRequired) },
                    binary = {
                        helper("while [ ! -f '$release' ]; do sleep 0.01; done\necho 'RIPDPI-CHECK|naiveproxy|ok'")
                    },
                    ioDispatcher = Dispatchers.IO,
                    protectPath = currentPath::get,
                )
            val check = async(Dispatchers.IO) { checker.check(profile, credentials) }
            try {
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) { while (!protectPathFile.exists()) delay(10) }
                }
                currentPath.set("/tmp/vpn-profile-new.sock")
                release.writeText("")

                assertEquals(NaiveProxyCheckOutcome.Failed, check.await())
                assertEquals("/tmp/vpn-profile-old.sock", protectPathFile.readText())
            } finally {
                release.writeText("")
                check.cancel()
            }
        }

    @Test
    fun `VPN check refuses to start without an active protect endpoint`() =
        runTest {
            val checker =
                NaiveProxyProfileChecker(
                    prepare = { _, _ -> config.copy(socketProtection = RelaySocketProtection.VpnRequired) },
                    binary = { helper("echo 'RIPDPI-CHECK|naiveproxy|ok'") },
                    ioDispatcher = Dispatchers.IO,
                )

            assertEquals(NaiveProxyCheckOutcome.Failed, checker.check(profile, credentials))
            assertFalse(argvFile.exists())
        }

    @Test
    fun `the helper result line maps to an outcome and credentials stay off argv`() =
        runTest {
            val outcome = checker("echo 'RIPDPI-CHECK|naiveproxy|auth'").check(profile, credentials)

            assertEquals(NaiveProxyCheckOutcome.Auth, outcome)
            val argv = argvFile.readText()
            assertTrue(argv, argv.startsWith("--check --listen "))
            assertFalse(argv, argv.contains("s3cret-pass") || argv.contains("alice"))
            val stdin = stdinFile.readText().lines()
            assertEquals("s3cret-pass", String(Base64.getDecoder().decode(stdin[1])))
        }

    @Test
    fun `unknown output, a hung helper and an invalid profile do not report ok`() =
        runTest {
            assertEquals(NaiveProxyCheckOutcome.Failed, checker("echo garbage").check(profile, credentials))
            val hung = checker("sleep 5", timeoutMillis = 200)
            assertEquals(NaiveProxyCheckOutcome.Timeout, hung.check(profile, credentials))
            val invalid =
                NaiveProxyProfileChecker(
                    prepare = { _, _ -> throw IllegalArgumentException("missing server") },
                    binary = { error("must not run") },
                    ioDispatcher = Dispatchers.IO,
                )
            assertEquals(NaiveProxyCheckOutcome.Failed, invalid.check(profile, credentials))
            // HelperExecutableLocator fails with IllegalStateException when the ABI ships no helper.
            val missing =
                NaiveProxyProfileChecker(
                    prepare = { _, _ -> config },
                    binary = { error("helper not packaged") },
                    ioDispatcher = Dispatchers.IO,
                )
            assertEquals(NaiveProxyCheckOutcome.Failed, missing.check(profile, credentials))
        }

    @Test
    fun `check lines parse strictly`() {
        assertEquals(NaiveProxyCheckOutcome.Ok, parseNaiveProxyCheckLine("RIPDPI-CHECK|naiveproxy|ok\n"))
        assertEquals(NaiveProxyCheckOutcome.Connect, parseNaiveProxyCheckLine("RIPDPI-CHECK|naiveproxy|connect"))
        assertEquals(NaiveProxyCheckOutcome.Failed, parseNaiveProxyCheckLine("RIPDPI-CHECK|naiveproxy|okay"))
        assertEquals(NaiveProxyCheckOutcome.Failed, parseNaiveProxyCheckLine("RIPDPI-CHECK|naiveproxy|http_connect"))
        assertEquals(NaiveProxyCheckOutcome.Failed, parseNaiveProxyCheckLine(null))
    }
}
