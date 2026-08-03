plugins {
    id("healthhub.android.feature")
}

dependencies {
    implementation(project(":core:network"))
    // For the sign-out path, which is more than dropping a token: the sync cursor belongs to
    // the account that was signed in, and `AccountSession` is the one place that knows it.
    implementation(project(":core:sync"))
}
