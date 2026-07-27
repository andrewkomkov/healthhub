plugins {
    id("healthhub.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // The source list and the athlete's unit system come from the shared client. The three
    // calls it does not carry — the archive page, the visibility patch and the priority write —
    // are made in SourcesRepository, so attaching these two screens touches no module outside
    // feature:sources (Constitution Principle VII, SC-012).
    implementation(project(":core:network"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // The ADB control surface is debug-only, and so is this module's contribution to it.
    debugImplementation(project(":core:devcontrol"))
}
