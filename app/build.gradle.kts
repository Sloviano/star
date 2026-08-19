import com.google.protobuf.gradle.id
import java.util.Properties

// Values sourced from local.properties (git-ignored) so they stay out of committed source.
val localProps: Properties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

// Default Apps Script endpoint. Empty when unset — the runtime Settings value always overrides it.
val defaultSheetsUrl: String = localProps.getProperty("SHEETS_URL")?.trim().orEmpty()

// Shared secret sent with every POST to the Apps Script Web App. The deployment is "Anyone"-access
// and unauthenticated, so without this the /exec URL alone grants write access to the sheet — and
// that URL travels widely (configs, chats, anyone who builds the app). Must match SHARED_SECRET in
// apps-script/Code.gs. Empty when unset, which the backend accepts. Use a hex secret
// (`openssl rand -hex 32`) — the value is interpolated into a generated Java string literal.
val sheetsToken: String = localProps.getProperty("SHEETS_TOKEN")?.trim().orEmpty()

// In-app updater (Module 6): the PUBLIC GitHub repo ("owner/repo") that hosts release APKs. Public,
// so the app checks it anonymously — no auth token ships in the APK. Not a secret.
val githubRepo: String = localProps.getProperty("GITHUB_REPO")?.trim().orEmpty()

// Release signing.
//
// Every APK published so far (v1.1 through v1.7) was a *debug* build signed with the local Android
// debug keystore, so that certificate is what the entire installed fleet trusts. Android will only
// let the in-app updater replace an installed app when the new APK carries the same signature, so
// switching to a fresh key would strand every technician's phone on its current build until someone
// uninstalls and reinstalls by hand — which also discards any records that haven't uploaded yet.
// The default below therefore keeps using that key deliberately, so the release variant is a
// drop-in replacement for the debug APKs that were being shipped.
//
// !! The keystore is machine-local and Android Studio silently regenerates it, with a DIFFERENT
// !! key, if the file goes missing. Back it up — losing it costs a manual reinstall on every phone.
//
// Point RELEASE_KEYSTORE at a real keystore in local.properties once you are ready to migrate the
// fleet; the three credential properties default to the well-known debug values.
val releaseKeystore: File = file(
    localProps.getProperty("RELEASE_KEYSTORE")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "${System.getProperty("user.home")}/.android/debug.keystore"
)

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
        versionCode = 8
        versionName = "1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default Apps Script Web App endpoint (from local.properties); override in Settings at runtime.
        buildConfigField("String", "DEFAULT_SHEETS_URL", "\"$defaultSheetsUrl\"")

        // Shared secret authenticating uploads to that endpoint (from local.properties).
        buildConfigField("String", "SHEETS_TOKEN", "\"$sheetsToken\"")

        // In-app updater: public "owner/repo" hosting release APKs (from local.properties).
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    signingConfigs {
        // Only declared when the keystore is actually present, so a checkout on a machine without
        // it still configures and can build debug — the release variant is what needs the key.
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = localProps.getProperty("RELEASE_KEYSTORE_PASSWORD")?.trim() ?: "android"
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")?.trim() ?: "androiddebugkey"
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")?.trim() ?: "android"
            }
        }
    }

    buildTypes {
        release {
            // Ship only the ABIs real phones use. ML Kit's OCR and barcode models are native, and
            // at ~16 MB per ABI they dominate the APK — carrying x86/x86_64 as well added ~33 MB
            // that no technician's device can ever execute, since those exist for emulators. Debug
            // builds stay universal so the emulator still works.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

            // Publish this variant, not the debug one. A debug APK is `android:debuggable`, which
            // hands anyone with USB access to a technician's phone the captured records, the Apps
            // Script URL and the shared secret straight out of app storage via `run-as`.
            signingConfig = signingConfigs.findByName("release")

            // R8: shrink, obfuscate, and drop unused resources. Keep rules for the reflective
            // libraries (protobuf-lite, gRPC, kotlinx.serialization) live in
            // src/main/keepRules/rules.keep, which AGP combines and passes to R8 automatically.
            //
            // Deliberately the classic full-shrink switches rather than AGP 9's `optimization {
            // enable = true }`: that one is the *gradual* R8 API, still behind the experimental
            // android.r8.gradual.support flag, and it only shrinks packages named in
            // `includePackages` — which would skip ML Kit, gRPC and CameraX, where the size is.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

    // OCR capture of the printed kit number, for a Data Matrix label that won't decode.
    // Deliberately the BUNDLED ML Kit artifact, not com.google.android.gms:play-services-mlkit-
    // text-recognition: the unbundled one fetches its model from Play Services on first use, and
    // first use here is a technician at a dish with no internet — exactly where it must not fail.
    implementation(libs.mlkit.text.recognition)

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
