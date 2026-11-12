import java.util.Properties

/** Your own Firebase project's config (see README); the build works without it, minus the cloud. */
val hasFirebase = file("google-services.json").exists()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.prabhat.djmix"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prabhat.djmix"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A clone without app/google-services.json still builds and runs: everything local, no cloud tab.
        buildConfigField("boolean", "CLOUD", hasFirebase.toString())
    }

    // Release signing from keystore.properties (never committed; see README). Without it, release builds are unsigned.
    val signing = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true // NewPipeExtractor uses java.time / java.nio on minSdk 24
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG gates the adb script extras, BuildConfig.CLOUD the Firebase features
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.firebase.storage)
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)

    // Dependency injection: Hilt generates the object graph at compile time (KSP).
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Jetpack Compose (versions come from the BOM) + ViewModel / lifecycle glue.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)

    // Google sign-in: Credential Manager gets a Google ID token, Firebase Auth turns it into a user.
    implementation(libs.firebase.auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.coroutines.play.services) // Task.await() for Firebase calls
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test) // the real org.json for IgAudio.parse; android.jar's is a stub
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
if (hasFirebase) apply(plugin = "com.google.gms.google-services")
