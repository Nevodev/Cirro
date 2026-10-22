import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.test)
}

evaluationDependsOn(":app")
val targetAndroid = project(":app").extensions.getByType<ApplicationExtension>()

android {
    namespace = "com.nevoit.cirro.macrobenchmark"
    compileSdk = targetAndroid.compileSdk
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = targetAndroid.defaultConfig.minSdk
        targetSdk = targetAndroid.defaultConfig.targetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.perfettoSdkTracing.enable"] = "true"
        testInstrumentationRunnerArguments["targetPackage"] =
            requireNotNull(targetAndroid.defaultConfig.applicationId)
        providers.gradleProperty("benchmark.iterations").orNull?.let {
            testInstrumentationRunnerArguments["iterations"] = it
        }
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.junit)
}
