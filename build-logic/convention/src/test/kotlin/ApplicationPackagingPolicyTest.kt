import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationPackagingPolicyTest {
    @Test
    fun `finds qualified and unqualified app bundle tasks`() {
        assertEquals(
            listOf("bundlePlayFullRelease", ":app:bundlePlaySimpleRelease"),
            requestedApplicationBundleTasks(
                taskNames =
                    listOf(
                        "bundlePlayFullRelease",
                        ":app:bundlePlaySimpleRelease",
                        ":app:assembleGithubFullRelease",
                    ),
                projectPath = ":app",
            ),
        )
    }

    @Test
    fun `ignores bundle tasks owned by other projects`() {
        assertEquals(
            emptyList(),
            requestedApplicationBundleTasks(
                taskNames =
                    listOf(
                        ":core:engine:bundleReleaseAar",
                        ":core:data:bundleLibRuntimeToJarRelease",
                    ),
                projectPath = ":app",
            ),
        )
    }
}
