plugins {
    id("healthhub.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:healthconnect"))

    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
