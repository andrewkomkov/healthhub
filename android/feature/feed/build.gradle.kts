plugins {
    id("healthhub.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.serialization.json)
}
