import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("com.google.dagger.hilt.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.kaislate.veldtplayer"
    compileSdk = 36

    // No "dependency metadata" block in the APK signing block. AGP writes it encrypted to a key
    // only Google can read. F-Droid's scanner rejects an APK carrying it, and it is not needed
    // for anything outside Google Play. Leaving it out also keeps release builds reproducible,
    // so F-Droid can publish the developer-signed APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "2.2.0"
    }

    defaultConfig {
        applicationId = "com.kaislate.veldtplayer"
        minSdk = 29
        targetSdk = 36
        // major*10000 + minor*100 + patch
        versionCode = 901
        versionName = "0.9.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing, the same mechanism as Veldt Wisp: an untracked key.properties at the
    // repo root (storeFile, storePassword, keyAlias, keyPassword). Without it, assembleRelease
    // still succeeds and produces an UNSIGNED APK.
    val keyProps = Properties().apply {
        val f = rootProject.file("key.properties")
        if (f.exists()) FileInputStream(f).use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = rootProject.file(keyProps["storeFile"] as String)
                storePassword = keyProps["storePassword"] as String
                keyAlias = keyProps["keyAlias"] as String
                keyPassword = keyProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keyProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Room (kapt — same processor family as Hilt to avoid mixing kapt/ksp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // WorkManager + Hilt bridge
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)

    // Tag reader (pure-JVM, LGPL-3.0)
    implementation(libs.ealvatag)

    implementation(libs.androidx.datastore.preferences)

    // Network stack for self-hosted sources (design spec §5.1). OkHttp is pure-JVM, so the
    // zero-native property holds; it is also already present transitively via Coil, which is
    // exactly why it is declared here — a Coil upgrade must not silently move it.
    implementation(libs.okhttp)
    // RUNTIME ONLY. There is no serialization compiler plugin in this project and there
    // cannot be one offline, so `@Serializable` is unavailable by construction; JSON is read
    // by navigating JsonElement. See Global Constraint 3.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // Test (Robolectric DAO test + pure coroutine tests)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real WorkManager under Robolectric. The single-flight property (unique work under
    // ExistingWorkPolicy.KEEP) is WorkManager's semantics, not ours — a hand-written fake
    // asserting it would be asserting its own model, so the real scheduler is used instead.
    testImplementation(libs.androidx.work.testing)
    // Compose UI tests, run under Robolectric in this JVM suite rather than on a device (Step 5
    // spec §1.4). The BOM pins both, as it does every other Compose artifact here, so the test
    // toolkit can never drift from the UI toolkit it drives.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    // debugImplementation, not testImplementation: this artifact is only a manifest entry
    // declaring the empty ComponentActivity that createComposeRule() launches, and Robolectric
    // resolves activities from the APP's merged manifest for the variant under test. A
    // test-classpath AAR's manifest is never merged into that, so the rule could not start its
    // activity. Debug-only keeps the stray activity out of release builds.
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
}
