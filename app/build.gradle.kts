import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Test-only (Task 8b JVM screenshot harness): writes Roborazzi PNGs, no production impact.
    alias(libs.plugins.roborazzi)
}

// Release signing (Task 9). Obtainium checks that every update is signed with the same key, so
// the real keystore is never committed: it is read from an untracked `keystore.properties` file
// at the repo root (see README "Making a release"). Without that file, the release build type
// below falls back to the debug key so `assembleRelease` still works for local/CI verification.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// versionCode/versionName are overridable per release build: `-PversionCode=2 -PversionName=1.1.0`.
val releaseVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val releaseVersionName = project.findProperty("versionName") as String? ?: "1.0.0"

android {
    namespace = "nz.personal.checkpointwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "nz.personal.checkpointwatch"
        minSdk = 29
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            if (hasKeystoreProperties) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasKeystoreProperties) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: keystore.properties not found at repo root — release build is " +
                        "falling back to the DEBUG signing key. This APK cannot be used to " +
                        "update an installation signed with the real release key. See README " +
                        "'Making a release'."
                )
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (Task 8b screenshot harness) needs app resources on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    // collectAsStateWithLifecycle: a StateFlow the screen stops collecting while it is stopped.
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.webkit)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test-only: Task 8b JVM screenshot harness (Robolectric + Roborazzi). Renders the stateless
    // Compose screens under app/src/test to PNGs for design review — no production dependency.
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Copies the signed release APK out of the variant-named build output into a stable, versioned
// name at the repo root, ready for `gh release create` (see README "Making a release").
tasks.register<Copy>("packageReleaseApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "checkpoint-watch-$releaseVersionName.apk" }
}
