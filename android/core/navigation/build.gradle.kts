plugins {
    id("healthhub.android.library")
    id("healthhub.android.compose")
}

dependencies {
    api(libs.navigation.compose)
    implementation(libs.compose.material.icons)
    // `@StringRes` on the labels a contributing module hands over. See `BottomBarEntry.label`.
    api(libs.androidx.annotation)
}
