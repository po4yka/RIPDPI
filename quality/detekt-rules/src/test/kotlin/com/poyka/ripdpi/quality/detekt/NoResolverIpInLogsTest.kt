package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class NoResolverIpInLogsTest {
    private val subject = NoResolverIpInLogs(Config.empty)

    @Test
    fun `reports Logger lambda with dns variable interpolation`() {
        val findings =
            subject.compileAndLint(
                """
                object Logger {
                    fun v(msg: () -> String) = msg()
                }
                fun test(dns: String) {
                    Logger.v { "DNS: ${'$'}dns" }
                }
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports Logger lambda with resolver variable interpolation`() {
        val findings =
            subject.compileAndLint(
                """
                object Logger {
                    fun d(msg: () -> String) = msg()
                }
                fun test(resolver: String) {
                    Logger.d { "upstream: ${'$'}resolver" }
                }
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports Logger lambda with upstream variable interpolation`() {
        val findings =
            subject.compileAndLint(
                """
                object Logger {
                    fun i(msg: () -> String) = msg()
                }
                fun test(upstream: String) {
                    Logger.i { "endpoint: ${'$'}upstream" }
                }
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `does not report Logger lambda with plain string message`() {
        val findings =
            subject.compileAndLint(
                """
                object Logger {
                    fun v(msg: () -> String) = msg()
                }
                fun test() {
                    Logger.v { "DNS configured" }
                }
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report Logger lambda with non-sensitive variable`() {
        val findings =
            subject.compileAndLint(
                """
                object Logger {
                    fun v(msg: () -> String) = msg()
                }
                fun test(count: Int) {
                    Logger.v { "DNS configured (${'$'}count resolvers)" }
                }
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report non-log calls with dns variable`() {
        val findings =
            subject.compileAndLint(
                """
                fun buildMessage(dns: String) = "DNS: ${'$'}dns"
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports block template entry with dnsIp variable`() {
        val findings =
            subject.compileAndLint(
                """
                object Logger {
                    fun w(msg: () -> String) = msg()
                }
                fun test(dnsIp: String) {
                    Logger.w { "addr: ${'$'}{dnsIp}" }
                }
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }
}
