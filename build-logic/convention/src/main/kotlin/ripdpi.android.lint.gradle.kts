import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint

the<CommonExtension>().lint.apply {
    abortOnError = true
    checkDependencies = true
    checkReleaseBuilds = true
    enable += setOf("Interoperability", "MissingTranslation")
    disable += setOf("ObsoleteLintCustomCheck")
    htmlReport = true
    xmlReport = true
    baseline = project.file("lint-baseline.xml")
}
