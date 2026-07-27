plugins {
    id("healthhub.android.library")
    id("healthhub.android.compose")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(libs.compose.material.icons)
}
