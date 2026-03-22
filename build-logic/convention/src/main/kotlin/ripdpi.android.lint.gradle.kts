import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint

the<CommonExtension>().lint.apply {
    abortOnError = true
    checkDependencies = true
    checkReleaseBuilds = true
    htmlReport = true
    xmlReport = true
    lintConfig = rootProject.file("lint.xml")
    baseline = project.file("lint-baseline.xml")
}
