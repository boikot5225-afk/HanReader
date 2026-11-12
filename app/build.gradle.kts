plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.hanreader.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hanreader.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 42
        versionName = "0.22.0"
        // Instrumented tests (app/src/androidTest) on emulators in CI.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Default neural voice server (reader can change it in the voice menu): gradle property,
        // else environment variable, else none. HANREADER_VOICE_TOKEN is a CI secret.
        fun setting(name: String) = (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull ?: "")
            .replace("\\", "").replace("\"", "")
        buildConfigField("String", "VOICE_URL", "\"${setting("HANREADER_VOICE_URL")}\"")
        buildConfigField("String", "VOICE_TOKEN", "\"${setting("HANREADER_VOICE_TOKEN")}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // zh/lexicon.dat is gzip data: store it as is. (A ".gz" asset would be silently unpacked by the build.)
    androidResources { noCompress += "dat" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Optional mirror for Robolectric's Android jars (set in ~/.gradle/gradle.properties).
            providers.gradleProperty("robolectricRepoUrl").orNull?.let {
                test.systemProperty("robolectric.dependency.repo.url", it)
            }
            providers.environmentVariable("HANREADER_TEST_EPUB").orNull?.let {
                test.environment("HANREADER_TEST_EPUB", it)
            }
            for (name in listOf("HANREADER_SEG_IN", "HANREADER_SEG_OUT")) {
                providers.environmentVariable(name).orNull?.let { test.environment(name, it) }
            }
            providers.environmentVariable("HANREADER_STORE_SHOTS").orNull?.let {
                test.environment("HANREADER_STORE_SHOTS", it)
            }
            providers.environmentVariable("HANREADER_COUNTS_OUT").orNull?.let {
                test.environment("HANREADER_COUNTS_OUT", it)
            }
        }
    }
    signingConfigs {
        // Shared, committed debug key so every test APK installs over the previous one.
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val signingFile = System.getenv("HANREADER_KEYSTORE_FILE")
            if (!signingFile.isNullOrBlank()) {
                storeFile = file(signingFile)
                storePassword = System.getenv("HANREADER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HANREADER_KEY_ALIAS")
                keyPassword = System.getenv("HANREADER_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!System.getenv("HANREADER_KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jsoup:jsoup:1.18.1")
    testImplementation("junit:junit:4.13.2")
    // Robolectric: run screens and the database on the JVM, without a device.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}

// Room writes each database version's schema here; keep them committed to check migrations.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
