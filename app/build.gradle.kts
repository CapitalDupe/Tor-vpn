plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourname.torproxychain"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yourname.torproxychain"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Keep off: the tun2socks JNI bridge (hev.htproxy.TProxyService) must not be renamed.
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // hev-socks5-tunnel prebuilt AAR (all ABIs). Drop hev-socks5-tunnel.aar into app/libs/.
    // See README.md. The app builds without it but cannot start the VPN.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
