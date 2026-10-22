import java.util.Properties
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 签名接线跟 PearWall 同一套：MOMENTO_SIGNING_* 四个属性（gradle.properties 或环境变量），
// 配齐了 release/debug 都用签名构建；一个都没配则静默跳过（出未签名包）。配置一半会直接报错。
fun signingProperty(name: String) =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name))

val signingStoreFile = signingProperty("MOMENTO_SIGNING_STORE_FILE")
val signingStorePassword = signingProperty("MOMENTO_SIGNING_STORE_PASSWORD")
val signingKeyAlias = signingProperty("MOMENTO_SIGNING_KEY_ALIAS")
val signingKeyPassword = signingProperty("MOMENTO_SIGNING_KEY_PASSWORD")
val signingProperties = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
)
val hasSigningProperties = signingProperties.any { it.isPresent }
val isSigningConfigured = signingProperties.all { it.isPresent }

check(!hasSigningProperties || isSigningConfigured) {
    "Cirro signing requires all MOMENTO_SIGNING_* properties to be configured"
}

val classicNdkVersion = "28.2.13676358"

android {
    namespace = "com.nevoit.cirro"
    ndkVersion = classicNdkVersion
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nevoit.cirro"
        minSdk = 30
        targetSdk = 37
        versionCode = 5
        versionName = "1.0"

        // 只出 arm64-v8a。x86_64 那份 `.so` 只服务模拟器，真机装不上也不该打进包 ——
        // 原生库占 APK 体积的大头，剔掉它等于直接砍掉约一半的原生负载。
        // `abiFilters` 对 `jniLibs/` 里手工放的预编译 `.so` 同样生效（不只是 ndk 构建产物）。
        // ⚠️ 要跑 x86_64 模拟器时，把 "x86_64" 加回来。
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (isSigningConfigured) {
            create("nev") {
                storeFile = file(signingStoreFile.get())
                storePassword = signingStorePassword.get()
                keyAlias = signingKeyAlias.get()
                keyPassword = signingKeyPassword.get()
            }
        }
    }
    buildTypes {
        debug {
            if (isSigningConfigured) {
                signingConfig = signingConfigs.getByName("nev")
            }
        }
        release {
            if (isSigningConfigured) {
                signingConfig = signingConfigs.getByName("nev")
            }
            optimization {
                enable = true
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            isDebuggable = false
            signingConfig = signingConfig ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":reorderable"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.util)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animation.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.kyant.shapes)
    implementation(libs.kyant.backdrop)
    implementation(libs.material.color.utilities)
    implementation(libs.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.effect)
    implementation(libs.media3.session)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.compose.viewmodel)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "benchmarkImplementation"(libs.androidx.compose.runtime.tracing)
    // Newer Android versions reject the writable native library injected by the test process.
    "benchmarkImplementation"(libs.androidx.tracing.perfetto.binary)
}

configurations.configureEach {
    exclude(group = "androidx.compose.material3")
}

val localSdkDirectory = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { propertiesFile ->
        Properties().apply { propertiesFile.inputStream().use(::load) }
            .getProperty("sdk.dir")
    }
val sdkDirectory = file(
    (localSdkDirectory
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME"))
        .replace("\\:", ":")
)
val nativeOutputDirectory = layout.buildDirectory.dir("generated/rust-jniLibs")
val buildClassicNative = tasks.register<Exec>("buildClassicNative") {
    val ndkDirectory = sdkDirectory.resolve("ndk/$classicNdkVersion")
    workingDir(rootProject.file("classic"))
    inputs.file(rootProject.file("classic/Cargo.toml"))
    inputs.file(rootProject.file("classic/Cargo.lock"))
    inputs.dir(rootProject.file("classic/src"))
    outputs.dir(nativeOutputDirectory)
    environment("ANDROID_NDK_HOME", ndkDirectory.absolutePath)
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-o",
        nativeOutputDirectory.get().asFile.absolutePath,
        "build", "--release",
    )
}
android.sourceSets["main"].jniLibs.directories.add(nativeOutputDirectory.get().asFile.absolutePath)
tasks.named("preBuild").configure { dependsOn(buildClassicNative) }
