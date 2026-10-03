import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 发布签名的密钥与口令**绝不进仓库**。
 *
 * 取值顺序：`keystore.properties`（仓库根目录的本地文件，已 gitignore）-> 环境变量。
 * 两处都没有时干脆**不注册**签名配置：release 产出未签名 APK，而不是让构建失败 ——
 * clone 本项目的人不带任何密钥也能把 `assembleRelease` 跑通。
 *
 * 环境变量名：MELODY_STORE_FILE / MELODY_STORE_PASSWORD / MELODY_KEY_ALIAS / MELODY_KEY_PASSWORD
 *
 * 注意 `Properties` 必须走文件顶部的 import：脚本里写 `java.util.Properties` 会被
 * Kotlin DSL 解析成 Gradle 的 `java` 扩展（JavaPluginExtension），报 Unresolved reference: util。
 */
val melodyKeystoreFile = rootProject.file("keystore.properties")
val melodyKeystoreProps = Properties()
if (melodyKeystoreFile.exists()) {
    melodyKeystoreFile.inputStream().use { stream -> melodyKeystoreProps.load(stream) }
}

fun melodySecret(propKey: String, envKey: String): String? =
    melodyKeystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val melodyStorePath = melodySecret("storeFile", "MELODY_STORE_FILE")
val melodyStorePwd = melodySecret("storePassword", "MELODY_STORE_PASSWORD")
val melodyKeyAliasName = melodySecret("keyAlias", "MELODY_KEY_ALIAS") ?: "melody"
val melodyKeyPwd = melodySecret("keyPassword", "MELODY_KEY_PASSWORD") ?: melodyStorePwd
val melodyStore = melodyStorePath?.let { rootProject.file(it) }
val hasMelodySigning = melodyStore != null && melodyStore.exists() && melodyStorePwd != null

android {
    namespace = "com.melody.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.melody.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 36
        versionName = "2.13"
        resourceConfigurations += listOf("zh", "en")

        // 工程自己已经没有原生代码了（LAME / libmelody_mp3.so 随「转 MP3」一并移除），
        // 但依赖里还带着 .so（androidx.graphics.path 就是 Compose 拉进来的），
        // 所以 abiFilters 留着：只打真机在用的两种 ABI，x86 那几份是模拟器专用。
        // 注意这里**不需要** ndkVersion，也没有 externalNativeBuild —— 只过滤、不编译。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 不再有 externalNativeBuild：工程里已经没有任何 C/C++ 源码需要编译。

    // 密钥齐备才注册（口令从 keystore.properties / 环境变量取，见文件开头的说明）。
    // 缺任何一项就完全不注册，fork 者零配置也能编过 —— 只是产出未签名包。
    signingConfigs {
        if (hasMelodySigning) {
            create("melody") {
                storeFile = melodyStore!!
                storePassword = melodyStorePwd!!
                keyAlias = melodyKeyAliasName
                keyPassword = melodyKeyPwd!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasMelodySigning) signingConfig = signingConfigs.getByName("melody")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 复用 release 签名，避免 debug/release 互相覆盖安装；没密钥就用 debug 默认签名
            if (hasMelodySigning) signingConfig = signingConfigs.getByName("melody")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // media3 的 @UnstableApi 是 RequiresOptIn(ERROR) 级别，不开这个开关会直接编译失败；
        // Material3 / Foundation 的实验性 API 同理，统一在编译期放开，免得每个文件都写 @OptIn
        freeCompilerArgs += listOf(
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-common:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
