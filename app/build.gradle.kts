plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}
android {
    namespace = "com.nexura.messenger"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nexura.messenger"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    val turnUrls = providers.gradleProperty("NEXURA_TURN_URLS")
        .orElse(providers.environmentVariable("NEXURA_TURN_URLS")).getOrElse("")
    val turnUsername = providers.gradleProperty("NEXURA_TURN_USERNAME")
        .orElse(providers.environmentVariable("NEXURA_TURN_USERNAME")).getOrElse("")
    val turnCredential = providers.gradleProperty("NEXURA_TURN_CREDENTIAL")
        .orElse(providers.environmentVariable("NEXURA_TURN_CREDENTIAL")).getOrElse("")
    fun buildConfigString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    defaultConfig {
        buildConfigField("String", "WEBRTC_TURN_URLS", buildConfigString(turnUrls))
        buildConfigField("String", "WEBRTC_TURN_USERNAME", buildConfigString(turnUsername))
        buildConfigField("String", "WEBRTC_TURN_CREDENTIAL", buildConfigString(turnCredential))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
}
