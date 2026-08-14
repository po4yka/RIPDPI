import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint

the<CommonExtension>().lint.apply {
    abortOnError = true
    checkDependencies = true
    checkReleaseBuilds = true
    lintConfig = rootProject.file("lint.xml")
}
