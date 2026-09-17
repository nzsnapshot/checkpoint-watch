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
val releaseVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 3
val releaseVersionName = project.findProperty("versionName") as String? ?: "1.0.2"

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

    // Google signs a block into every APK listing its dependencies, encrypted to a Play key.
    // This app is never going near Play, so the block is nothing but an opaque passenger.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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

/**
 * The gate between "a release build" and "a release".
 *
 * `assembleRelease` is allowed to fall back to the debug key, because it is also how the release
 * build is smoke-tested locally. Packaging is not: an APK signed with the debug key and named
 * `checkpoint-watch-1.2.0.apk` in `dist/` is indistinguishable from the real thing at the moment
 * someone reaches for it, and Android will refuse to install it over the app on the phone —
 * signature changes are exactly what Obtainium and the platform are checking for.
 */
val requireReleaseKeystore = tasks.register("requireReleaseKeystore") {
    group = "verification"
    description = "Fails unless keystore.properties exists, so a debug-signed APK can never be packaged as a release."
    doFirst {
        check(hasKeystoreProperties) {
            "Refusing to package a release APK: keystore.properties was not found at the " +
                "repository root, so this build would be signed with the DEBUG key. An APK " +
                "signed with a different key cannot update the app already on the phone — " +
                "Obtainium and Android both refuse it — and once it is sitting in dist/ under a " +
                "release name there is nothing to tell it apart from a real one.\n" +
                "  * To cut a real release: create keystore.properties (see README, " +
                "\"Making a release\").\n" +
                "  * To smoke-test the release build locally: ./gradlew assembleRelease, which " +
                "falls back to the debug key on purpose and leaves its APK in the build " +
                "directory."
        }
    }
}

// Copies the signed release APK out of the variant-named build output into a stable, versioned
// name under dist/, ready for `gh release create` (see README "Making a release").
tasks.register<Copy>("packageReleaseApk") {
    dependsOn(requireReleaseKeystore, "assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "checkpoint-watch-$releaseVersionName.apk" }
}

// So the refusal above arrives in seconds rather than after a full release build. Only ever in
// force when both tasks are in the graph, which is only ever `packageReleaseApk`.
// (Matched by name rather than named(): the Android plugin creates its variant tasks later than
// this file is evaluated.)
tasks.matching { it.name == "assembleRelease" }.configureEach {
    mustRunAfter(requireReleaseKeystore)
}
