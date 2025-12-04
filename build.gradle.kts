plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // 移除了 libs.plugins.kotlin.compose 插件，因为我们使用 XML 布局
}

android {
    namespace = "com.example.harapp"

    // 修正后的标准格式：
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.harapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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

    // 添加 ViewBinding 支持，以便更安全、便捷地操作视图
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // 核心库
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // View System (XML 布局) 依赖
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.cardview:cardview:1.0.0")

    // TFLite 依赖 (用于模型推理)
    implementation("org.tensorflow:tensorflow-lite:2.15.0")

    // 【新增】Apache Commons Math 依赖 (用于复杂的数学和信号处理，例如 FFT)
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")

    // ------------------- 移除所有 Compose 依赖 -------------------
    // implementation(libs.androidx.activity.compose)
    // implementation(platform(libs.androidx.compose.bom))
    // ...
    // -----------------------------------------------------------

    // 测试依赖
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}