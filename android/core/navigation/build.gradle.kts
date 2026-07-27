plugins {
    id("healthhub.android.library")
    id("healthhub.android.compose")
}

dependencies {
    api(libs.navigation.compose)
    implementation(libs.compose.material.icons)
}
