import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

evaluationDependsOn(":app")
val appAndroid = project(":app").extensions.getByType<ApplicationExtension>()

android {
    namespace = "sh.calvin.reorderable"
    compileSdk = appAndroid.compileSdk

    defaultConfig {
        minSdk = appAndroid.defaultConfig.minSdk
    }

    compileOptions {
        sourceCompatibility = appAndroid.compileOptions.sourceCompatibility
        targetCompatibility = appAndroid.compileOptions.targetCompatibility
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
}
