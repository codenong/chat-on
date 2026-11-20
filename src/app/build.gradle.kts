// DeepSeek 续聊助手 —— app 模块

import java.util.Properties

val keystoreProps = Properties().apply {
    val propFile = rootProject.file("keystore.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chatcontinuation"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.chatcontinuation"
        // WebViewAssetLoader、WebMessageListener、Keystore AES-GCM 均可用；
        // WebView 本体需设备上较新的 System WebView（Play 通道自动更新）。
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.2.9"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        val storePath = keystoreProps.getProperty("storeFile")
        ?: System.getenv("SIGNING_STORE_FILE")
        if (!storePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                ?: System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias")
                ?: System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword")
                ?: System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro"
            )
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
    testOptions {
        // 单测中 android.* 桩方法（如 Log.i）返回默认值而非抛异常
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // WebViewAssetLoader / WebMessageListener / addDocumentStartJavaScript
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation("junit:junit:4.13.2")
    // 单测中使用真实 org.json 实现（android.jar 桩不可用）
    testImplementation("org.json:json:20240303")
}
