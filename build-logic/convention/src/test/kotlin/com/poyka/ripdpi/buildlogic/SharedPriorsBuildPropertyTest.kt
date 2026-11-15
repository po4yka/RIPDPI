package com.poyka.ripdpi.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedPriorsBuildPropertyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `release inputs use first non-blank Gradle local then environment value`() {
        val directory = temporaryFolder.newFolder()
        File(directory, "settings.gradle").writeText("rootProject.name = 'shared-priors-inputs'")
        val classpath =
            listOf(
                SharedPriorsBuildProperty::class.java.protectionDomain.codeSource.location,
                Unit::class.java.protectionDomain.codeSource.location,
            ).joinToString(",") { "'${File(it.toURI()).invariantSeparatorsPath}'" }
        File(directory, "build.gradle").writeText(
            """
            buildscript { dependencies { classpath files($classpath) } }
            def manifest = com.poyka.ripdpi.buildlogic.SharedPriorsBuildProperty.INSTANCE.resolve(
                project, 'sharedPriors.manifestUrl', 'RIPDPI_SHARED_PRIORS_MANIFEST_URL')
            def priors = com.poyka.ripdpi.buildlogic.SharedPriorsBuildProperty.INSTANCE.resolve(
                project, 'sharedPriors.priorsUrl', 'RIPDPI_SHARED_PRIORS_PRIORS_URL')
            tasks.register('readReleaseInputs') {
                doLast {
                    println('MANIFEST=' + manifest.get())
                    println('PRIORS=' + priors.get())
                }
            }
            """.trimIndent(),
        )
        val testKitDirectory = temporaryFolder.newFolder()
        val environment = System.getenv().filterKeys { !it.startsWith("RIPDPI_SHARED_PRIORS_") }
        val environmentUrls =
            environment +
                mapOf(
                    "RIPDPI_SHARED_PRIORS_MANIFEST_URL" to " https://env.test/manifest ",
                    "RIPDPI_SHARED_PRIORS_PRIORS_URL" to " https://env.test/priors ",
                )

        fun verify(
            expectedManifest: String,
            expectedPriors: String,
            inputs: Map<String, String>,
            vararg arguments: String,
        ) {
            val output =
                GradleRunner
                    .create()
                    .withProjectDir(directory)
                    .withTestKitDir(testKitDirectory)
                    .withEnvironment(inputs)
                    .withArguments("readReleaseInputs", "--stacktrace", "--max-workers=2", *arguments)
                    .build()
                    .output
            assertTrue(output, output.contains("MANIFEST=$expectedManifest\n"))
            assertTrue(output, output.contains("PRIORS=$expectedPriors\n"))
        }
        verify("", "", environment)
        verify("https://env.test/manifest", "https://env.test/priors", environmentUrls)
        File(directory, "local.properties").writeText(
            "sharedPriors.manifestUrl= https://local.test/manifest \n" +
                "sharedPriors.priorsUrl= https://local.test/priors \n",
        )
        verify("https://local.test/manifest", "https://local.test/priors", environmentUrls)
        verify(
            "https://gradle.test/manifest",
            "https://gradle.test/priors",
            environmentUrls,
            "-PsharedPriors.manifestUrl= https://gradle.test/manifest ",
            "-PsharedPriors.priorsUrl= https://gradle.test/priors ",
        )
        verify(
            "https://local.test/manifest",
            "https://local.test/priors",
            environmentUrls,
            "-PsharedPriors.manifestUrl= ",
            "-PsharedPriors.priorsUrl= ",
        )
        File(directory, "local.properties").writeText(
            "sharedPriors.manifestUrl= \nsharedPriors.priorsUrl= \n",
        )
        verify(
            "https://env.test/manifest",
            "https://env.test/priors",
            environmentUrls,
            "-PsharedPriors.manifestUrl= ",
            "-PsharedPriors.priorsUrl= ",
        )
    }
}
