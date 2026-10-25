import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.google.services)
}

// Reads an env var, treating empty as "not set" (GitHub passes empty strings for unset variables).
fun env(name: String, default: String): String =
  System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

android {
  namespace = "com.hyperboost.engine"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    // Keep the original applicationId for update continuity. The Kotlin namespace is different
    // because the app started as an AI Studio project; changing it would break existing installs.
    applicationId = "com.aistudio.hyperboost.mkvxyz"
    minSdk = 26
    targetSdk = 34
    // CI passes these from the git tag (see .github/workflows/release.yml); local builds use the defaults.
    versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 8
    versionName = System.getenv("VERSION_NAME") ?: "1.7.3"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Unity Ads. Override any of these with env vars / GitHub repo Variables (see RELEASING.md).
    buildConfigField("String", "UNITY_GAME_ID", "\"${env("UNITY_GAME_ID", "800403406")}\"")
    buildConfigField("boolean", "UNITY_TEST_MODE", (env("UNITY_TEST_MODE", "false") != "false").toString())
    buildConfigField("String", "UNITY_BANNER_ID", "\"${env("UNITY_BANNER_ID", "BP_Banner_Android")}\"")
    buildConfigField("String", "UNITY_INTERSTITIAL_ID", "\"${env("UNITY_INTERSTITIAL_ID", "BP_Interstitial_Android")}\"")
    buildConfigField("String", "UNITY_REWARDED_ID", "\"${env("UNITY_REWARDED_ID", "BP_Rewarded_Android")}\"")
  }

  // Release signing comes from env vars / GitHub secrets (KEYSTORE_PATH, STORE_PASSWORD,
  // KEY_PASSWORD). When they are absent (forks, first local build) the build falls back to the
  // debug key so assembleRelease still produces an installable APK.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  val hasReleaseKey = !System.getenv("STORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("KEY_PASSWORD").isNullOrBlank() &&
    file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks").exists()
  // debug.keystore is git-ignored; if it is missing use AGP's auto-generated default debug key.
  val fallbackDebugSigning =
    if (file("${rootDir}/debug.keystore").exists()) signingConfigs.getByName("debugConfig")
    else signingConfigs.getByName("debug")

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else fallbackDebugSigning
    }
    debug {
      signingConfig = fallbackDebugSigning
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  // Release lint was failing the build over a transitive (Unity) library version; keep it as a
  // report instead of a build blocker.
  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Unity placement IDs and signing credentials come from environment variables above.
// No Secrets Gradle Plugin is used, so deleting .env.example cannot break the build.
googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.auth)
  implementation("com.unity3d.ads:unity-ads:4.20.0")
  implementation("androidx.fragment:fragment-ktx:1.8.5")
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}