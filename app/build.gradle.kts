plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.portable.qqmusic"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    /**
     * 签名配置。
     *
     * 默认复用 debug.keystore 给 release 包签名，这样 `assembleRelease` 出来的 APK
     * 就能直接安装（未签名的包在设备上会报「解析安装包时出现问题」）。
     * 正式发布时请替换成自己的 keystore，或删除 release 的 signingConfig 以恢复未签名产物。
     */
    signingConfigs {
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // 同时启用 v1 与 v2: v1 覆盖 Android 7.0 上的老式校验路径，
            // v2 提供完整性保护，两者并存兼容性最好
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.portable.qqmusic"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        // 只保留中文与英文资源, 显著减小 APK 体积与资源解析开销
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 低开销优先: 不启用 Compose / ViewBinding / DataBinding, 全部使用轻量 XML + findViewById
    buildFeatures {
        buildConfig = true
        compose = false
        viewBinding = false
        dataBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 基础框架 —— 全部为 AndroidX, 无协程 / 无反射库依赖, 控制体积与启动开销
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.11.0")

    // 媒体通知 (MediaStyle + MediaSessionCompat)
    implementation("androidx.media:media:1.6.0")

    // 图片加载: 4.16.0 已适配 Android 13/14 通知与权限, 磁盘缓存可复用系统缓存目录
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // 二维码生成 (ZXing core, 约 500KB, 不引入 android-embedded 避免 Activity 注入)
    implementation("com.google.zxing:core:3.5.3")

    // 从封面图取主色: 播放详情页配色跟着封面走
    implementation("androidx.palette:palette-ktx:1.0.0")

    debugImplementation("androidx.tracing:tracing:1.1.0")
}
