plugins {
    id("healthhub.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:telemetry"))
    api(project(":core:database"))
    api(project(":core:network"))
    api(project(":core:healthconnect"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
