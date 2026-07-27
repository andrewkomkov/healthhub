plugins {
    id("healthhub.android.library")
    id("healthhub.android.compose")
}

dependencies {
    // The generated token object is the only source of colour, type, shape and motion.
    implementation(libs.compose.material.icons)
}
