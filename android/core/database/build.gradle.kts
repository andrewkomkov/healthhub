plugins {
    id("healthhub.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
}

// Room's generated schemas are checked in so a migration can be reviewed as a diff.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
