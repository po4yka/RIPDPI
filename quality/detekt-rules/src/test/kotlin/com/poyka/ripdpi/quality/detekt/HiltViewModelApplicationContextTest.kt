package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class HiltViewModelApplicationContextTest {
    private val subject = HiltViewModelApplicationContext(io.gitlab.arturbosch.detekt.api.Config.empty)

    @Test
    fun `allows hilt viewmodel constructors without application context`() {
        val findings =
            subject.compileAndLint(
                """
                import dagger.hilt.android.lifecycle.HiltViewModel
                import javax.inject.Inject

                class Dependency

                @HiltViewModel
                class SampleViewModel @Inject constructor(
                    private val dependency: Dependency,
                )
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports application context on hilt viewmodel primary constructor`() {
        val findings =
            subject.compileAndLint(
                """
                import android.content.Context
                import dagger.hilt.android.lifecycle.HiltViewModel
                import dagger.hilt.android.qualifiers.ApplicationContext
                import javax.inject.Inject

                @HiltViewModel
                class SampleViewModel @Inject constructor(
                    @ApplicationContext private val context: Context,
                )
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports application context on hilt viewmodel secondary constructor`() {
        val findings =
            subject.compileAndLint(
                """
                import android.content.Context
                import dagger.hilt.android.lifecycle.HiltViewModel
                import dagger.hilt.android.qualifiers.ApplicationContext

                @HiltViewModel
                class SampleViewModel {
                    constructor(@ApplicationContext context: Context)
                }
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `allows application context outside hilt viewmodels`() {
        val findings =
            subject.compileAndLint(
                """
                import android.content.Context
                import dagger.hilt.android.qualifiers.ApplicationContext
                import javax.inject.Inject

                class Sample @Inject constructor(
                    @ApplicationContext private val context: Context,
                )
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }
}
