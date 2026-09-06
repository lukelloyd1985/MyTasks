plugins {
    alias(libs.plugins.android.application)
    // Kotlin sources compile via AGP's built-in Kotlin support (default
    // since AGP 9.0) - no separate org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "com.github.lukelloyd1985.mytasklist"
    // 36, not 37: API 37 isn't published as an installable stable SDK
    // platform yet (see the `appwrite` version comment in
    // gradle/libs.versions.toml for how this was actually confirmed, not
    // assumed) - `sdkmanager "platforms;android-37"` in CI genuinely fails
    // with "Failed to find package", regardless of cmdline-tools version.
    // A previous fix here wrongly assumed that error was just a stale
    // package listing and bumped this to 37 anyway (matching what
    // io.appwrite:sdk-for-android 26.0.0+ requires via AAR metadata) -
    // pinning `appwrite` back to 25.2.0 removes that requirement instead,
    // so 36 compiles cleanly again. 36 already satisfies Play's minimum
    // targetSdk requirement (36+ from August 31, 2026 - see README
    // "Publishing to Google Play").
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.lukelloyd1985.mytasklist"
        minSdk = 31 // Android 12
        // Matches compileSdk above (also satisfies Google Play's minimum
        // requirement to target API 36+ from August 31, 2026 - see README
        // "Publishing to Google Play").
        targetSdk = 36

        // Play Store rejects any upload whose versionCode isn't strictly
        // greater than every previous upload's. GITHUB_RUN_NUMBER
        // increments on every run of this workflow, so it's a reliable
        // monotonic source in CI; local builds fall back to 1.
        // RELEASE_VERSION_NAME is set by the release workflow job to the
        // git tag (e.g. "v1.2.3"); local builds fall back to "1.0.0-dev".
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "1.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Appwrite project ID isn't sensitive - like the Firebase
        // project identifiers below, it only identifies the project (no
        // access without a session/API key) and ships inside the compiled
        // APK regardless of how it's configured here.
        // So rather than duplicate it in a GitHub secret AND a separate
        // local/CI env var (two places the same value has to be pasted
        // and kept in sync), it's read directly from
        // appwrite/appwrite.json's own "projectId" field - the same file
        // deploy-appwrite.yml already pushes from, and the single place a
        // contributor standing up their own backend needs to set it (see
        // README "Backend setup").
        val appwriteProjectId = Regex("\"projectId\"\\s*:\\s*\"([^\"]*)\"")
            .find(rootProject.file("appwrite/appwrite.json").readText())
            ?.groupValues?.get(1) ?: ""

        // The rest of the Appwrite connection details are fixed IDs this
        // codebase itself chose (not assigned by Appwrite), matching
        // appwrite/appwrite.json's own $id fields - env-var overrides
        // exist only for a contributor customizing them, not because
        // they're expected to vary per-environment like the project ID.
        buildConfigField("String", "APPWRITE_ENDPOINT", "\"${System.getenv("APPWRITE_ENDPOINT") ?: "https://cloud.appwrite.io/v1"}\"")
        buildConfigField("String", "APPWRITE_PROJECT_ID", "\"$appwriteProjectId\"")
        buildConfigField("String", "APPWRITE_DATABASE_ID", "\"${System.getenv("APPWRITE_DATABASE_ID") ?: "mytasklist"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_USERS_ID", "\"${System.getenv("APPWRITE_COLLECTION_USERS_ID") ?: "users"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_LISTS_ID", "\"${System.getenv("APPWRITE_COLLECTION_LISTS_ID") ?: "lists"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_TASKS_ID", "\"${System.getenv("APPWRITE_COLLECTION_TASKS_ID") ?: "tasks"}\"")
        buildConfigField("String", "APPWRITE_FUNCTION_MAINTENANCE_ID", "\"${System.getenv("APPWRITE_FUNCTION_MAINTENANCE_ID") ?: "maintenance"}\"")

        // The Google Cloud OAuth 2.0 Web application Client ID used two
        // places: Credential Manager's GetGoogleIdOption/
        // GetSignInWithGoogleOption.setServerClientId() (see LoginScreen.kt)
        // so the ID token it returns is minted for this client, and the
        // maintenance Function's GOOGLE_WEB_CLIENT_ID variable, which
        // verifies that same ID token's audience server-side - both must
        // reference the identical Client ID. Not a secret (Client IDs are
        // meant to be embedded in client code), see README "Backend setup".
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${System.getenv("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")

        // Firebase Cloud Messaging is the only Firebase surface this app
        // still uses (see README "Architecture" - Appwrite Messaging now
        // owns everything except the on-device FCM token itself). Rather
        // than the google-services Gradle plugin + a committed
        // google-services.json, FirebaseApp is initialized manually in
        // MyTaskListApp.onCreate() from these values - none of them are
        // secrets (they're the same non-sensitive identifiers
        // google-services.json would have carried, just supplied directly
        // instead of through a generated file + plugin), so this follows
        // the same BuildConfig-from-env-var pattern as every other config
        // value here rather than introducing a second, inconsistent
        // mechanism for one library. Firebase Console → Project settings →
        // General → your Android app is where these come from - see
        // README "Backend setup".
        //
        // Project ID, API key, and Sender ID are project-level - Firebase
        // auto-creates a single Android API key per *project*, not per
        // app: registering a second Android app (e.g. the debug package
        // name below) adds that app's package name + SHA-1 as another
        // entry to the *same* key's Android restrictions in Google Cloud
        // Console, rather than minting a separate key (confirmed against
        // a real project - Google Cloud Console → Credentials shows one
        // "Android key (auto created by Firebase)" regardless of how many
        // Android apps are registered). So these three live here in
        // defaultConfig, shared by every build type. App ID is the one
        // exception - unique per registered app - so it's declared per
        // build type below instead (release: com.github.lukelloyd1985.mytasklist;
        // debug: the .debug applicationIdSuffix variant) - see the
        // buildTypes block.
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${System.getenv("FIREBASE_PROJECT_ID") ?: ""}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${System.getenv("FIREBASE_API_KEY") ?: ""}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${System.getenv("FIREBASE_SENDER_ID") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            // Store and key password are always the same value: keytool
            // defaults to PKCS12 keystores now, which don't support a
            // separate per-key password (keytool silently ignores -keypass
            // and reuses the store password) - so there's only one
            // password secret to configure, not two. The alias isn't a
            // secret either (see README "Building the APK") - it's fixed
            // and already public there, so it's hardcoded rather than
            // read from an env var.
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "mytasklist"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
        // GitHub Actions runners are a fresh VM every run, so with no
        // override here AGP's built-in debug signing would auto-generate a
        // brand-new, random debug.keystore on every CI build. Google Sign-In
        // verifies the calling app's signing certificate as part of its
        // account-reauth check, so a debug APK signed with a different,
        // unregistered certificate every run fails that check every time
        // (surfaces as GetCredentialException type TYPE_USER_CANCELED,
        // message "[16] Account reauth failed"). Overriding with a stable,
        // CI-provided keystore (see README) - whose SHA-1 gets registered in
        // Firebase once - fixes this. Falls back to AGP's default debug
        // signing (unaffected) for local builds where this isn't set.
        getByName("debug") {
            val keystorePath = System.getenv("DEBUG_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")
                keyAlias = "mytasklistdebug"
                keyPassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Own Firebase Android app registration (package name
            // com.github.lukelloyd1985.mytasklist.debug, from
            // applicationIdSuffix above) - App ID is unique per registered
            // app, so reusing the release app's App ID here would report
            // this build under the wrong app in Firebase Console/Crashlytics
            // (the shared API key above still works for both, see its own
            // comment - only App ID needs to differ). See README "Backend
            // setup" step 8.
            buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${System.getenv("FIREBASE_APPLICATION_ID_DEBUG") ?: ""}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val keystorePath = System.getenv("KEYSTORE_PATH")
            signingConfig = if (!keystorePath.isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                // Falls back to the debug keystore so `assembleRelease` still
                // produces an installable, unsigned-for-store APK for manual
                // testing builds when release-signing secrets aren't configured.
                signingConfigs.getByName("debug")
            }
            buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${System.getenv("FIREBASE_APPLICATION_ID") ?: ""}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No explicit Kotlin jvmTarget: built-in Kotlin defaults it from
    // compileOptions.targetCompatibility above.

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

// Publishes the release App Bundle to Google Play via `publishReleaseBundle`
// (see .github/workflows/android-build.yml and README "Publishing to Google
// Play"). Configuring this block never requires credentials - only running
// a publish task does, via the ANDROID_PUBLISHER_CREDENTIALS environment
// variable (a Play Console service account's JSON key) - so this is a no-op
// for every other build until that's set.
play {
    // "alpha" is the Play Developer API's identifier for the default
    // Closed testing track (Play Console's own naming; "internal"/"beta"/
    // "production" are the other three built-in tracks - a custom-named
    // closed testing track would need its actual name here instead).
    track.set("alpha")
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(platform(libs.compose.bom))
    // Kept solely so firebase-messaging-ktx (the one Firebase surface this
    // migration keeps, as the FCM push transport) resolves its version -
    // every other Firebase dependency that used to come off this BOM
    // (auth/firestore/functions) is gone.
    implementation(platform(libs.firebase.bom))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.firebase.messaging)

    // io.appwrite:sdk-for-android is still the correct, actively-maintained
    // client SDK for Android/Kotlin (it was not merged into
    // io.appwrite:sdk-for-kotlin, which is a separate server-side SDK - see
    // its README's "If you're looking for the Android SDK..." note). Version
    // pin verified in gradle/libs.versions.toml against the SDK's own
    // README/CHANGELOG.
    implementation(libs.appwrite)

    // Credential Manager + Google ID - see LoginScreen.kt for the sign-in
    // flow these back (native "Sign in with Google" picker; no browser
    // redirect, no appwrite.io shown to the user at any point).
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
