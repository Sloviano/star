import com.google.protobuf.gradle.id
import java.util.Properties

// Values sourced from local.properties (git-ignored) so they stay out of committed source.
val localProps: Properties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

// Default Apps Script endpoint. Empty when unset — the runtime Settings value always overrides it.
val defaultSheetsUrl: String = localProps.getProperty("SHEETS_URL")?.trim().orEmpty()

// In-app updater (Module 6): the PUBLIC GitHub repo ("owner/repo") that hosts release APKs. Public,
// so the app checks it anonymously — no auth token ships in the APK. Not a secret.
val githubRepo: String = localProps.getProperty("GITHUB_REPO")?.trim().orEmpty()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.starlink.scanner"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.starlink.scanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default Apps Script Web App endpoint (from local.properties); override in Settings at runtime.
        buildConfigField("String", "DEFAULT_SHEETS_URL", "\"$defaultSheetsUrl\"")

        // In-app updater: public "owner/repo" hosting release APKs (from local.properties).
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Room writes the current schema here on every build. The JSON is checked in: it is what a future
// migration is written against and verified by, now that a missing migration is a hard failure
// instead of a silent table drop (see AppDatabase).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    plugins {
        id("grpc") { artifact = libs.grpc.protoc.gen.java.get().toString() }
        id("grpckt") { artifact = libs.grpc.protoc.gen.kotlin.get().toString() + ":jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
                id("kotlin") { option("lite") }
            }
            task.plugins {
                id("grpc") { option("lite") }
                id("grpckt") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Barcode scanning (Module 3): CameraX preview/analysis + ML Kit barcode model.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // gRPC to the dish's local API (Module 1). OkHttp transport (not netty) for Android.
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin.lite)
    compileOnly(libs.javax.annotation.api)

    // Deferred, batched upload to Google Sheets (Module 5): WorkManager job + OkHttp POST + JSON DTOs.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
