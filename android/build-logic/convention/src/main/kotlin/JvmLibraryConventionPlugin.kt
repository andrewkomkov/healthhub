import org.gradle.api.Plugin
import org.gradle.api.Project

/** For pure-Kotlin modules with no Android dependency — core:model and the codec's maths. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        configureJavaToolchain()
        configureUnitTests()
    }
}
