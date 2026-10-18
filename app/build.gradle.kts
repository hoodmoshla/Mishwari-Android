plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val permanentSigning = listOf(
    "MISHWARI_KEYSTORE_PATH", "MISHWARI_STORE_PASSWORD",
    "MISHWARI_KEY_ALIAS", "MISHWARI_KEY_PASSWORD"
).associateWith { providers.environmentVariable(it).orNull }

android {
    namespace = "ye.mishwari"
    compileSdk = 35
    defaultConfig {
        applicationId = "ye.mishwari"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
    }
    signingConfigs {
        create("mishwariPermanent") {
            storeFile = permanentSigning["MISHWARI_KEYSTORE_PATH"]?.let { file(it) }
            storePassword = permanentSigning["MISHWARI_STORE_PASSWORD"]
            keyAlias = permanentSigning["MISHWARI_KEY_ALIAS"]
            keyPassword = permanentSigning["MISHWARI_KEY_PASSWORD"]
            storeType = "PKCS12"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("mishwariPermanent")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Never produce an unsigned/fallback Release when permanent credentials are absent.
gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any { task ->
        task.project == project && task.name.contains("Release") &&
            listOf("assemble", "bundle", "package", "install").any { prefix -> task.name.startsWith(prefix) }
    }
    if (packagesRelease) {
        val missing = permanentSigning.filterValues { it.isNullOrBlank() }.keys
        check(missing.isEmpty()) { "Permanent Mishwari signing credentials missing: ${missing.joinToString()}" }
        check(file(permanentSigning.getValue("MISHWARI_KEYSTORE_PATH")!!).isFile) {
            "Permanent Mishwari keystore file is missing; refusing Release build"
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    androidTestImplementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
