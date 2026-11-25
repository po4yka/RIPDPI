import com.android.build.api.dsl.CommonExtension

the<CommonExtension>().lint.apply {
    abortOnError = true
    checkDependencies = true
    checkReleaseBuilds = providers.gradleProperty("ripdpi.lintReleaseBuilds").map(String::toBoolean).getOrElse(true)
    ignoreTestSources =
        !providers.gradleProperty("ripdpi.lintTestSources").map(String::toBoolean).getOrElse(true)
    lintConfig = rootProject.file("lint.xml")
}
