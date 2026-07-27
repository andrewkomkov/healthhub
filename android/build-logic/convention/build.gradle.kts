plugins {
    `kotlin-dsl`
}

group = "dev.healthhub.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
}

/**
 * Convention plugins keep the fifteen module scripts to three lines each. Without them, every
 * SDK level, compiler flag and toolchain setting would be repeated fifteen times and would
 * drift the first time one of them changed.
 */
gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "healthhub.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "healthhub.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "healthhub.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "healthhub.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
