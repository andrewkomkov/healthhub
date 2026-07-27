plugins {
    id("healthhub.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // The detail response and the `.hht` objects it points at. The screen reads them; it never
    // recomputes a metric — every figure it shows was produced by core:telemetry at ingest.
    implementation(project(":core:network"))
    implementation(project(":core:telemetry"))
    implementation(libs.kotlinx.serialization.json)

    // Importing one activity's GPS track (R-015). The consent contract comes from
    // core:healthconnect and the re-ingest from core:sync — this module asks for the track and
    // renders the outcome, and computes none of it, which is the same division the rest of the
    // screen already keeps to.
    implementation(project(":core:healthconnect"))
    implementation(project(":core:sync"))

    // Two calls the shared client does not carry — the detail row and the telemetry object — are
    // made here so that attaching this screen touched no module outside feature:activity.
    implementation(libs.okhttp)

    // Session 1 step 5 draws the route with MapLibre Native. Declared here rather than left
    // as an unused catalogue entry so that the version actually resolves before the agent
    // that needs it finds out.
    implementation(libs.maplibre)

    // The ADB control surface is debug-only, and so is this module's contribution to it.
    debugImplementation(project(":core:devcontrol"))
}
