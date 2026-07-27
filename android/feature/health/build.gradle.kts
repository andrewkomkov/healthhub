plugins {
    id("healthhub.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Health Connect types are not transitive. :core:healthconnect exposes them with `api(...)`
    // rather than `implementation(...)`, which is the only reason a record type named in this
    // module's own signatures resolves at all.
    implementation(project(":core:healthconnect"))

    // Turning a domain on has to do something the same minute. Without HealthRecordSync,
    // enabling sleep would grant a permission and then show an empty screen until the next
    // scheduled pass — which reads, correctly, as broken.
    implementation(project(":core:sync"))

    // The /api/health-records reads these surfaces need are made in HealthRepository, the way
    // feature:sources makes its three: the shared client and token store are reused, but no
    // module outside this one learns about a route only these screens use (SC-012).
    implementation(project(":core:network"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // The ADB control surface is debug-only, and so is this module's contribution to it.
    debugImplementation(project(":core:devcontrol"))
}
