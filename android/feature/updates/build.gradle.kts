plugins {
    id("healthhub.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // The releases API is JSON over HTTPS and nothing else; the module talks to GitHub
    // directly rather than through core:network, which carries the device token and must
    // never send it anywhere but the Worker.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
