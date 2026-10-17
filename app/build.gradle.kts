plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version name and monotonically increasing Android versionCode are maintained in one file.
val appVersion = java.util.Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName = appVersion.getProperty("versionName")
    ?: error("Missing versionName in version.properties")
val appVersionCode = appVersion.getProperty("versionCode")?.toIntOrNull()
    ?: error("Invalid versionCode in version.properties")

// The signing key is deliberately not stored in this public repository.
val stableKeyPath = System.getenv("SMENNIK_SIGNING_FILE")
val stableKeyPassword = System.getenv("SMENNIK_SIGNING_PASSWORD")

android {
    val stableReleaseSigning =
        if (!stableKeyPath.isNullOrBlank() && !stableKeyPassword.isNullOrBlank()) {
            signingConfigs.create("stableRelease") {
                storeFile = file(stableKeyPath)
                storePassword = stableKeyPassword
                keyAlias = "smennik"
                keyPassword = stableKeyPassword
            }
        } else null

    namespace = "ru.smennik.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.smennik.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (stableReleaseSigning != null) {
                signingConfig = stableReleaseSigning
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
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
