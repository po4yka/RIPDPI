import com.android.build.api.dsl.CommonExtension

the<CommonExtension>().lint.apply {
    abortOnError = true
    checkDependencies = true
    // CI release verification passes false: gradle-static-analysis already runs
    // full lint, and release-candidate builds keep the lintVital gate.
    checkReleaseBuilds = providers.gradleProperty("ripdpi.lintReleaseBuilds").map(String::toBoolean).getOrElse(true)
    // Pull requests pass ripdpi.lintFullScope=false and skip test sources;
    // pushes to main still lint them.
    ignoreTestSources = !providers.gradleProperty("ripdpi.lintFullScope").map(String::toBoolean).getOrElse(true)
    lintConfig = rootProject.file("lint.xml")
}
