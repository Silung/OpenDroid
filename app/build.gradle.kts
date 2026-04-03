import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

private fun String.escapeForBuildConfigString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "dev.opendroid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.opendroid.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        val localProps = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use { localProps.load(it) }
        }
        val injectedApiKey = System.getenv("OPENDROID_DEFAULT_API_KEY")
            ?: localProps.getProperty("opendroid.default.api.key")
            ?: ""
        val defaultBaseUrl = "https://api.siliconflow.cn/"
        val defaultModel = "zai-org/GLM-4.6V"
        buildConfigField(
            "String",
            "DEFAULT_LLM_API_KEY",
            "\"${injectedApiKey.escapeForBuildConfigString()}\"",
        )
        buildConfigField(
            "String",
            "DEFAULT_LLM_BASE_URL",
            "\"${defaultBaseUrl.escapeForBuildConfigString()}\"",
        )
        buildConfigField(
            "String",
            "DEFAULT_LLM_MODEL",
            "\"${defaultModel.escapeForBuildConfigString()}\"",
        )
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":device-bridge"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.extended.icons)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
