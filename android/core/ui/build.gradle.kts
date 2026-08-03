plugins {
    id("healthhub.android.library")
    id("healthhub.android.compose")
}

dependencies {
    // `api`, not `implementation`: `Format` takes a `UnitSystem` and `SectionCard` is shaped by
    // the Expressive shape scale, so both are in this module's public signature and every caller
    // has to be able to name them. A feature module gets them from the convention plugin anyway;
    // a type on a public API belongs on the public classpath regardless.
    api(project(":core:model"))
    api(project(":core:designsystem"))
    implementation(libs.compose.material.icons)
    // `@StringRes` on the sport lookup, so a resource id cannot be passed where an int is meant.
    implementation(libs.androidx.annotation)
}
