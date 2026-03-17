package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class InjectConstructorDefaultParameterTest {
    private val subject = InjectConstructorDefaultParameter(io.gitlab.arturbosch.detekt.api.Config.empty)

    @Test
    fun `reports default value on primary injected constructor`() {
        val findings =
            subject.compileAndLint(
                """
                import javax.inject.Inject

                class Dependency

                class Sample @Inject constructor(
                    private val dependency: Dependency = Dependency(),
                )
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports default value on secondary injected constructor`() {
        val findings =
            subject.compileAndLint(
                """
                import javax.inject.Inject

                class Dependency

                class Sample {
                    @Inject
                    constructor(dependency: Dependency = Dependency())
                }
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `allows injected constructors without default values`() {
        val findings =
            subject.compileAndLint(
                """
                import javax.inject.Inject

                class Dependency

                class Sample @Inject constructor(
                    private val dependency: Dependency,
                )
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `allows default values on non injected constructors`() {
        val findings =
            subject.compileAndLint(
                """
                class Dependency

                class Sample(
                    private val dependency: Dependency = Dependency(),
                )
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }
}
