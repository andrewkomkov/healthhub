import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * The shape every feature module has: Compose UI, Hilt, and the core modules a feature is
 * allowed to see.
 *
 * Feature modules deliberately get no dependency on one another — Constitution Principle VII.
 * Cross-feature needs go through interfaces owned by core, which is what lets a social module
 * be added later without editing anything that exists today.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("healthhub.android.library")
        pluginManager.apply("healthhub.android.compose")
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:navigation"))

            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
            add("implementation", libs.findLibrary("lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("navigation-compose").get())
            add("implementation", libs.findLibrary("hilt-navigation-compose").get())
            add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
        }
    }
}
