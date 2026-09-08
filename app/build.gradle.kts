import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
android {
    namespace = "com.skypatrol.groundstation"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.skypatrol.groundstation"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.0.2-mini3"
        manifestPlaceholders["DJI_APP_KEY"] = local.getProperty("DJI_APP_KEY", "")
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { buildConfig = false }
    signingConfigs.getByName("debug") {
        local.getProperty("DEBUG_KEYSTORE")?.let { storeFile = rootProject.file(it) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/*.so"
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.dji:dji-sdk-v5-aircraft:5.18.0")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.18.0")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:5.18.0")
    testImplementation("junit:junit:4.13.2")
}
