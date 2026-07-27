plugins {
    id("healthhub.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
}

// The golden .hht fixture is shared with the web workspace, so it lives outside this module
// and Gradle cannot see it as an input on its own. Without this, editing the fixture leaves
// the test task up to date and the check that is supposed to catch codec drift never runs.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.layout.projectDirectory.file("../fixtures/hht/golden-v1.hht"))
        .withPropertyName("hhtGoldenFixture")
        .withPathSensitivity(PathSensitivity.NONE)
        .optional()
}
