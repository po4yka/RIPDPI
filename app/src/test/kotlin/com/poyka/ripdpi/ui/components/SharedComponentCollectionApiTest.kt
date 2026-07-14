package com.poyka.ripdpi.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedComponentCollectionApiTest {
    @Test
    fun `public shared composables expose immutable collection contracts`() {
        val violations =
            componentSourceRoot()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    PublicComposable.findAll(file.readText()).mapNotNull { match ->
                        val parameters = match.groupValues[2]
                        if (RawCollection.containsMatchIn(parameters)) {
                            "${file.relativeTo(componentSourceRoot()).path}:${match.groupValues[1]}"
                        } else {
                            null
                        }
                    }
                }.toList()

        assertTrue("Raw List/Set parameters in public shared composables: $violations", violations.isEmpty())
    }

    private fun componentSourceRoot(): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/ui/components"),
            File("app/src/main/kotlin/com/poyka/ripdpi/ui/components"),
        ).first(File::isDirectory)

    private companion object {
        val PublicComposable =
            Regex(
                pattern = """@Composable\s+(?:public\s+)?fun\s+(?:<[^>]+>\s*)?(\w+)\s*\((.*?)\)\s*(?::[^\{=]+)?[\{=]""",
                option = RegexOption.DOT_MATCHES_ALL,
            )
        val RawCollection = Regex("""(?<![A-Za-z0-9_])(List|Set)<""")
    }
}
