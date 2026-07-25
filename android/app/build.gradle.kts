import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "com.earceo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.earceo.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "VIAIM_APP_KEY",
            buildConfigString(localProperties.getProperty("viaim.appKey", "")),
        )
        buildConfigField(
            "String",
            "VIAIM_APP_SECRET",
            buildConfigString(localProperties.getProperty("viaim.appSecret", "")),
        )
        buildConfigField(
            "String",
            "EARCEO_BACKEND_URL",
            buildConfigString(localProperties.getProperty("earceo.backendUrl", "")),
        )
        buildConfigField(
            "String",
            "EARCEO_API_TOKEN",
            buildConfigString(localProperties.getProperty("earceo.apiToken", "")),
        )
        buildConfigField(
            "String",
            "EARCEO_PROJECT_ID",
            buildConfigString(
                localProperties.getProperty("earceo.projectId", "adventurex-demo"),
            ),
        )
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(files("libs/VisionHeadsetOpen-v1.0.0.aar"))

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.polidea.rxandroidble2:rxandroidble:1.10.1")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("net.java.dev.jna:jna:5.6.0@aar")
    testImplementation("junit:junit:4.13.2")
}
