plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    // UPDATED: Changed from displaydetails to posprint
    namespace = "com.example.posprint"
    compileSdk = 35

    defaultConfig {
        // UPDATED: Changed from displaydetails to posprint
        applicationId = "com.example.posprint"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // I have commented out these two lines because they were pulling the
    // "too new" versions (alpha/beta) that caused the error.
    // implementation(libs.androidx.core.ktx)
    // implementation(libs.androidx.activity)

    // These are the Fixed Stable Versions (Corrected syntax):
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity:1.9.3")

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}