import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun props(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}

// 本机私密配置（已 gitignore）：OpenAI 密钥、签名密钥库密码
val secrets = props("secrets.properties")
// 版本号（提交到仓库），release.sh 每次发布加一
val versionProps = props("version.properties")

fun quoted(v: String) = "\"" + v.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// 只有显式加 -PembedKey=true 才把 OpenAI 密钥打进 APK（给自己手动安装用）。
// 发到 GitHub 的版本一律不带密钥；App 第一次运行时会把带进来的密钥存到本机，之后的更新包不带也能继续用。
val embedKey = (findProperty("embedKey") as String?) == "true"
val versionCodeValue = (findProperty("versionCodeOverride") as String?)?.toInt()
    ?: (versionProps.getProperty("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "app.quanwen"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.quanwen"
        minSdk = 30
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionProps.getProperty("VERSION_NAME") ?: "0.1"
        buildConfigField("String", "OPENAI_API_KEY", quoted(if (embedKey) secrets.getProperty("OPENAI_API_KEY") ?: "" else ""))
        buildConfigField("String", "OPENAI_BASE_URL", quoted(secrets.getProperty("OPENAI_BASE_URL") ?: "https://api.openai.com/v1"))
        buildConfigField("String", "UPDATE_REPO", quoted("ttxs66666/quanwen"))
    }

    signingConfigs {
        create("release") {
            val ks = secrets.getProperty("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = rootProject.file(ks)
                storePassword = secrets.getProperty("KEYSTORE_PASSWORD")
                keyAlias = secrets.getProperty("KEY_ALIAS")
                keyPassword = secrets.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (secrets.getProperty("KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
