package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class DisallowNewSuppressionTest {
    private val subject = DisallowNewSuppression(io.gitlab.arturbosch.detekt.api.Config.empty)

    @Test
    fun `reports suppress annotation without roadmap comment`() {
        val findings =
            subject.compileAndLint(
                """
                @Suppress("LongMethod")
                fun foo() {}
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `allows suppress annotation with roadmap comment on preceding line`() {
        val findings =
            subject.compileAndLint(
                """
                // ROADMAP-architecture-refactor W5
                @Suppress("LongMethod")
                fun foo() {}
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `allows suppress annotation with roadmap comment on same line`() {
        val findings =
            subject.compileAndLint(
                """
                @Suppress("LongMethod") // ROADMAP-architecture-refactor inline allowlist
                fun foo() {}
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports file-level suppress annotation without roadmap comment`() {
        val findings =
            subject.compileAndLint(
                """
                @file:Suppress("TooManyFunctions")

                fun foo() {}
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `allows file-level suppress annotation with roadmap comment on preceding line`() {
        val findings =
            subject.compileAndLint(
                """
                // ROADMAP-architecture-refactor W5
                @file:Suppress("TooManyFunctions")

                fun foo() {}
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not report non-suppress annotations`() {
        val findings =
            subject.compileAndLint(
                """
                annotation class MyAnnotation

                @MyAnnotation
                fun foo() {}
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }
}
