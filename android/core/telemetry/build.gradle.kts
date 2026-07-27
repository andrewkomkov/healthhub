plugins {
    id("healthhub.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
}
