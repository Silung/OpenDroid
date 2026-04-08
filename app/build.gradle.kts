import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

private fun String.escapeForBuildConfigString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

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
        val defaultBaseUrl = localProps.getProperty("opendroid.default.llm.base.url")
            ?: "https://api.siliconflow.cn/v1"
        val defaultModel = localProps.getProperty("opendroid.default.llm.model")
            ?: "zai-org/GLM-4.6V"
        val defaultOmniparserParseUrl = localProps.getProperty("opendroid.default.omniparser.parse.url")
            ?: ""
        val defaultLlmApiFormat = when (
            localProps.getProperty("opendroid.default.llm.api.format")?.trim()?.lowercase().orEmpty()
        ) {
            "anthropic" -> "anthropic"
            else -> "openai"
        }
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
        buildConfigField(
            "String",
            "DEFAULT_OMNIPARSER_PARSE_URL",
            "\"${defaultOmniparserParseUrl.escapeForBuildConfigString()}\"",
        )
        buildConfigField(
            "String",
            "DEFAULT_LLM_API_FORMAT",
            "\"${defaultLlmApiFormat.escapeForBuildConfigString()}\"",
        )
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")!!
                keyPassword = keystoreProperties.getProperty("keyPassword")!!
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile")!!)
                storePassword = keystoreProperties.getProperty("storePassword")!!
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
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
