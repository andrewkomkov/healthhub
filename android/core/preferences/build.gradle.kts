plugins {
    id("healthhub.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    // `api`: the flows this module publishes are typed on the domain's own enums, so anything
    // that reads a preference has to be able to name one.
    api(project(":core:model"))

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
