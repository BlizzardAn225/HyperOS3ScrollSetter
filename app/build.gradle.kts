plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blizzard225.wallpapersetter"
    // libxposed service/interface 102.0.0 的 AAR 元数据要求 compileSdk>=37；
    // 本机安装的是小版本平台 android-37.0，须配 compileSdkMinor
    compileSdk = 37
    compileSdkMinor = 0
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl

            output.outputFileName =
                "WallpaperSetter_v${versionName}_release.apk"
        }
    }

    defaultConfig {
        applicationId = "com.blizzard225.wallpapersetter"
        minSdk = 29
        targetSdk = 34
        versionCode = 1800
        versionName = "1.8.0"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 现代 libxposed API（compileOnly：运行时由框架注入实现）
    compileOnly("io.github.libxposed:api:102.0.0")
    // 模块 app 与框架通信（激活检测/远程配置；自带 XposedProvider，manifest merger 自动合并）
    implementation("io.github.libxposed:service:102.0.0")
}
