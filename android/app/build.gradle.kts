import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val pocketSigning = Properties()
val pocketSigningFile = rootProject.file("../.private/android-signing.properties")
if (pocketSigningFile.exists()) pocketSigningFile.inputStream().use { pocketSigning.load(it) }
android {
    namespace = "dev.codexpocket.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.codexpocket.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 16
        versionName = "0.7.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (pocketSigningFile.exists()) create("pocketRelease") {
            storeFile = rootProject.file("../.private/android-release.jks")
            storePassword = pocketSigning.getProperty("storePassword")
            keyAlias = "codex-pocket"
            keyPassword = pocketSigning.getProperty("storePassword")
        }
    }
    buildTypes {
        debug { if (pocketSigningFile.exists()) signingConfig = signingConfigs.getByName("pocketRelease") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (pocketSigningFile.exists()) signingConfig = signingConfigs.getByName("pocketRelease")
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.work:work-runtime-ktx:2.10.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("io.noties.markwon:core:4.6.2")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
