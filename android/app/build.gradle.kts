plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    // HTTP client supports WebDAV extensions such as PROPFIND.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
android {
    namespace = "eu.x45.cal45.sync"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.x45.cal45.sync"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.12.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
