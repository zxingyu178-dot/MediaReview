import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release 签名:读取 git 忽略的 key.properties;缺失或字段不全时 Release 构建必须失败(禁止 debug 签名兜底)。
val keystoreProps = Properties()
val keystoreFile = rootProject.file("key.properties")
if (keystoreFile.exists()) {
    keystoreFile.inputStream().use { keystoreProps.load(it) }
}
val missingKeyField = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .firstOrNull { !keystoreProps.containsKey(it) }

android {
    namespace = "com.mediareview.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mediareview.app"
        minSdk = 26
        targetSdk = 35
        // 版本规则见 docs/VERSION_POLICY.md：versionCode 只能递增，
        // 每次产品版本变更必须同步补充 feature/v2/releasenotes 的 Release Notes。
        versionCode = 11
        versionName = "2.0.0-alpha4"
        // Stage 8B.1 §25：instrumentation 使用生产 Application（MediaReviewApp 负责 GSY Exo2 内核注册）；
        // Hilt 测试宿主 HiltTestActivity 位于 debug 源集（测试进程与 App 同进程）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (missingKeyField != null) {
                // 缺失签名信息: 指向不存在的密钥文件,让 AGP 的 validateSigningRelease 使 Release 构建失败。
                storeFile = file("__MISSING_RELEASE_KEY__")
            } else {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 强制使用 release 签名;缺失密钥时构建失败,不静默回退 debug 签名。
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    /**
     * JVM 单测（`testDebugUnitTest`）没有 Android 运行时：
     * `android.util.Log` 等系统类默认会抛 "not mocked"。
     * 性能打点 [com.mediareview.app.feature.v2.perf.V2Perf] 会在 Debug 构建调用 Log.i，
     * 这里按 AGP 标准做法返回默认值，避免测试因为"日志"而失败（不改变任何生产行为）。
     */
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Image
    implementation(libs.coil.compose)

    // Media3 播放器（Stage 8D §11 依赖审计结论）：
    // 旧自研 Media3 播放器（core/media/PlayerCore、V2PlayerScreen、PlayerController）已删除，
    // 生产源码不再直接 import androidx.media3；但 GSY Exo2 内核在运行时依赖 Media3
    // （dependencyInsight 显示 media3-exoplayer / media3-ui 由 gsyvideoplayer-exo2 传递提供，
    // 而 media3-exoplayer-hls 未被传递保证，HLS 回退仍需要）。
    // 依据 "禁止为清理导致 GSY runtime 缺类"，这里显式保留三者，不做直接依赖删除。
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    // GSYVideoPlayer（Stage2.1 Wrapper 模式验证；Exo2 内核，Media3 1.10.1）
    implementation(libs.gsyvideoplayer.compose)
    implementation(libs.gsyvideoplayer.exo2)

    // Paging 3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.paging.testing)
    // Stage 8A：Production Repository 合同测试（MockWebServer 真实 HTTP）
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // Stage 8B：设备侧批阅会话端到端（MockWebServer 在模拟器内直接起服务，不依赖宿主机 Mock Server）
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
