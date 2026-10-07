plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sree.sasi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sree.sasi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Shared debug signing key: every CI build uses the same key, so test APKs
    // install as updates instead of forcing an uninstall first. The keystore
    // bytes are checked in as base64 text (app/debug.keystore.b64) and decoded
    // to app/debug.keystore at configuration time — byte-exact, no manual
    // binary upload needed. Standard debug key (android/androiddebugkey),
    // safe to keep in the repo.
    val sharedKeyB64 = file("debug.keystore.b64")
    if (sharedKeyB64.exists()) {
        file("debug.keystore").writeBytes(
            java.util.Base64.getMimeDecoder().decode(sharedKeyB64.readText())
        )
    }
    signingConfigs {
        getByName("debug") {
            val sharedKey = file("debug.keystore")
            if (sharedKey.exists()) {
                storeFile = sharedKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
}
