plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Lab endpoint is only ever injected into debug builds. Release builds ship with the
// feature switched off so no test network target can leak into a production APK.
val labBaseUrlDebug = "http://10.0.2.2:5000/"

android {
    namespace = "com.example.expensetracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.expensetracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "LAB_ENABLED_BY_DEFAULT", "false")
        buildConfigField("String", "LAB_DEFAULT_BASE_URL", "\"$labBaseUrlDebug\"")
        // Cleartext HTTP is permitted only for the isolated emulator lab; see README.
        // 10.0.2.2 = Android Studio emulator host loopback, 10.0.3.2 = Genymotion host gateway.
        buildConfigField("String", "LAB_ALLOWED_HOSTS", "\"10.0.2.2,10.0.3.2,localhost,127.0.0.1\"")
        manifestPlaceholders["allowCleartextHttp"] = "false"
    }

    // The release build is signed with the local debug key so the course/lab APK installs
    // without committing a keystore or its password. Replace this with a real signingConfig
    // before publishing anywhere.
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            manifestPlaceholders["allowCleartextHttp"] = "true"
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Production builds never talk to the lab server and never allow cleartext HTTP.
            buildConfigField("boolean", "LAB_ENABLED_BY_DEFAULT", "false")
            buildConfigField("String", "LAB_DEFAULT_BASE_URL", "\"\"")
            buildConfigField("String", "LAB_ALLOWED_HOSTS", "\"\"")
            manifestPlaceholders["allowCleartextHttp"] = "false"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
