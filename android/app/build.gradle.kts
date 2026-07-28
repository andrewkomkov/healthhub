import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The deployment this build talks to. Overridable from local.properties so a fork points at
 * its own Worker without editing tracked files.
 */
val baseUrl: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("HEALTHHUB_BASE_URL") ?: "https://healthhub.andrew-komkov.workers.dev"

/**
 * The release signing identity, or null when this machine does not hold it.
 *
 * Read from the environment first so CI can pass it in without writing anything to disk, then
 * from `local.properties` so a developer holding the key can build a real release locally.
 * Absent, the release build still *builds* — it comes out unsigned, which is what a fork or a
 * pull request should get, rather than a build failure that reads like a broken project.
 */
val releaseKeystore: Map<String, String>? = run {
    val local = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun value(env: String, property: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: local.getProperty(property)?.takeIf { it.isNotBlank() }

    val path = value("ANDROID_KEYSTORE_PATH", "androidKeystorePath") ?: return@run null
    val storePassword = value("ANDROID_KEYSTORE_PASSWORD", "androidKeystorePassword") ?: return@run null
    val alias = value("ANDROID_KEY_ALIAS", "androidKeyAlias") ?: return@run null
    val keyPassword = value("ANDROID_KEY_PASSWORD", "androidKeyPassword") ?: return@run null
    if (!File(path).exists()) return@run null
    mapOf("path" to path, "storePassword" to storePassword, "alias" to alias, "keyPassword" to keyPassword)
}

android {
    namespace = "dev.healthhub"
    compileSdk = 37 // required by Material 3 Expressive alpha

    defaultConfig {
        applicationId = "dev.healthhub"
        minSdk = 28
        targetSdk = 36
        // The tag is the version. CI passes both in; a local build gets the fallback, and the
        // fallback is deliberately 1 rather than a date, so a hand-built APK never outranks a
        // released one on the phone it is installed over.
        versionCode = (System.getenv("ANDROID_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "0.1.0"

        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    signingConfigs {
        releaseKeystore?.let { key ->
            create("release") {
                storeFile = File(key.getValue("path"))
                storePassword = key.getValue("storePassword")
                keyAlias = key.getValue("alias")
                keyPassword = key.getValue("keyPassword")
                // v1 as well, because minSdk is 28 and an APK installed by hand on a phone that
                // predates v2-only verification is exactly the install path this build is for.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // The ADB control surface (Constitution Principle VIII) is compiled into debug
            // builds only; the release build must not contain it.
            buildConfigField("boolean", "DEV_CONTROL", "true")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "DEV_CONTROL", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:navigation"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:healthconnect"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:sync"))

    // Feature modules are wired in here and nowhere else. Adding a social module later means
    // adding one line to this list — no existing file changes.
    implementation(project(":feature:auth"))
    implementation(project(":feature:feed"))
    implementation(project(":feature:activity"))
    implementation(project(":feature:sync"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:sources"))
    implementation(project(":feature:health"))
    implementation(project(":feature:about"))

    debugImplementation(project(":core:devcontrol"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // The catalogue's junit-jupiter carries no version — the BOM supplies it, and Gradle
    // needs the launcher on the test runtime classpath to start a worker at all.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
