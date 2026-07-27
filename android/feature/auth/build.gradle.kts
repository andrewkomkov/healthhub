plugins {
    id("healthhub.android.feature")
}

dependencies {
    // Core modules only — a feature never depends on another feature (Principle VII).
    implementation(project(":core:network"))
}
