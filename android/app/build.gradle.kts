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

android {
    namespace = "dev.healthhub"
    compileSdk = 37 // required by Material 3 Expressive alpha

    defaultConfig {
        applicationId = "dev.healthhub"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // The ADB control surface (Constitution Principle VIII) is compiled into debug
            // builds only; the release build must not contain it.
            buildConfigField("boolean", "DEV_CONTROL", "true")
        }
        release {
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
