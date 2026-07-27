import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            // Derives dev.healthhub.core.telemetry from :core:telemetry, so a new module needs
            // no namespace bookkeeping.
            namespace = "dev.healthhub." + path.removePrefix(":").replace(':', '.')
            configureAndroidCommon(this)
            // AGP 9 removed targetSdk from library modules — it only ever meant anything for
            // the application, which sets it in app/build.gradle.kts.
        }

        configureUnitTests()
    }
}
