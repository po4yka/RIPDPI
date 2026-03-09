import com.android.build.api.dsl.CommonExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

the<CommonExtension>().apply {
    buildFeatures.compose = true
}
