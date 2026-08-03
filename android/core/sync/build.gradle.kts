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
    api(project(":core:preferences"))

    implementation(libs.kotlinx.serialization.json)
    // The /api/health-records calls are made here rather than in core:network, reusing the
    // shared OkHttpClient and TokenStore so timeouts and credentials keep one definition.
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
