plugins {
    id("healthhub.android.feature")
}

dependencies {
    implementation(project(":core:sync"))
    implementation(project(":core:healthconnect"))
}
