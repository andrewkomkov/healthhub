import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal const val MIN_SDK = 28
internal const val TARGET_SDK = 36 // applied by the application module only
internal const val COMPILE_SDK = 37 // required by Material 3 Expressive alpha
internal val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_21

internal val Project.libs
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * SDK levels, Java version and compiler flags, applied once instead of fifteen times.
 *
 * Note that AGP 9 dropped `CommonExtension`'s type parameters and brought Kotlin support
 * in-tree, so the old `org.jetbrains.kotlin.android` plugin must not be applied and the
 * Kotlin JVM target is set on the compile tasks rather than through a Kotlin extension.
 */
internal fun Project.configureAndroidCommon(extension: LibraryExtension) {
    extension.apply {
        compileSdk = COMPILE_SDK

        defaultConfig {
            minSdk = MIN_SDK
        }

        compileOptions {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}

internal fun Project.configureCompose(extension: LibraryExtension) {
    extension.apply {
        buildFeatures {
            compose = true
        }
    }

    dependencies {
        add("implementation", platform(libs.findLibrary("compose-bom").get()))
        add("implementation", libs.findLibrary("compose-ui").get())
        add("implementation", libs.findLibrary("compose-ui-graphics").get())
        add("implementation", libs.findLibrary("compose-foundation").get())
        add("implementation", libs.findLibrary("compose-material3").get())
        // Here rather than per feature: an icon is design-system vocabulary, and a module having
        // to declare a dependency to draw a back arrow is how screens end up with a text button
        // where a navigation icon belongs.
        add("implementation", libs.findLibrary("compose-material-icons-core").get())
        add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
        add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
    }
}

internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    dependencies {
        add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
        add("testImplementation", libs.findLibrary("junit-jupiter").get())
        add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
        add("testImplementation", libs.findLibrary("truth").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    }
}

internal fun Project.configureJavaToolchain() {
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
}
