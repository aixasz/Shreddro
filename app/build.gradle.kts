import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Secrets live in local.properties (gitignored); Gradle does not load it
// automatically, so read it here. CI can supply the same keys as -P props.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String): String =
    localProps.getProperty(key) ?: (findProperty(key) as? String) ?: ""

android {
    namespace = "com.shreddro.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shreddro.app"
        minSdk = 29 // Android 10 — scoped storage baseline
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"

        // AppAuth redirect scheme (Google). Microsoft uses msauth scheme in manifest.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.shreddro.app"

        // Injected from local.properties / CI -P props — never hardcode.
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${secret("shreddro.googleClientId")}\"")
        buildConfigField("String", "MS_OAUTH_CLIENT_ID", "\"${secret("shreddro.msClientId")}\"")
        buildConfigField("String", "APPS_SCRIPT_URL", "\"${secret("shreddro.appsScriptUrl")}\"")
        buildConfigField("String", "APPS_SCRIPT_SECRET", "\"${secret("shreddro.appsScriptSecret")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Release signing: config comes from local.properties or CI -P props.
    // Without one, the release APK builds unsigned (installable only after
    // signing); the debug APK is always installable for sideload testing.
    val storeFilePath = secret("shreddro.storeFile")
    if (storeFilePath.isNotBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = secret("shreddro.storePassword")
                keyAlias = secret("shreddro.keyAlias")
                keyPassword = secret("shreddro.keyPassword")
            }
        }
    }

    // Tesseract ships native libs per ABI; splits keep each install small.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (storeFilePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OAuth 2.0 / OIDC (Google + Microsoft via generic AppAuth PKCE flow)
    implementation("net.openid:appauth:0.11.1")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // On-device gate: ML Kit Thai text + barcode (QR)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    // Task.await() bridge used by MlKitSlipValidator
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Offline Thai OCR (Tesseract; tha+eng fast models in assets/tessdata)
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.7.0")

    testImplementation(kotlin("test"))
}
