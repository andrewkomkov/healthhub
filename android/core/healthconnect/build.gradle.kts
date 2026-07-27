plugins {
    id("healthhub.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    // Exposed rather than internal: the route contract hands back core:model's RoutePoint, so
    // the type is part of this module's public API.
    api(project(":core:model"))

    api(libs.health.connect)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
