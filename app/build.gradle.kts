plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 隐藏调试功能（关于弹窗连点清档/全解锁）：由 gradle.properties 的 focusDebugTools 控制，
// 发布时置 false 重新编译即随 BuildConfig.DEBUG_TOOLS 关闭。
val focusDebugTools = (findProperty("focusDebugTools") as? String)?.toBooleanStrictOrNull() ?: false

android {
    namespace = "me.hebin.focus"
    compileSdk = 34

    defaultConfig {
        applicationId = "me.hebin.focus"
        minSdk = 24
        targetSdk = 34
        versionCode = 13
        versionName = "0.9.1"
        buildConfigField("boolean", "DEBUG_TOOLS", focusDebugTools.toString())
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
