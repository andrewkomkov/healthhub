plugins {
    id("healthhub.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.security.crypto)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

// The password pre-hash vectors are shared with the web workspace, so they live outside this
// module and Gradle cannot see them as an input on its own. Without this, editing the fixture
// leaves the test task up to date and the check that catches KDF drift never runs.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.layout.projectDirectory.file("../fixtures/auth/prehash-v1.json"))
        .withPropertyName("passwordPreHashFixture")
        .withPathSensitivity(PathSensitivity.NONE)
        .optional()
}
