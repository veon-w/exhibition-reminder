import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// 簽名金鑰放 android/keystore.properties(gitignored);沒有就用 debug 金鑰簽 release
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 內建資料網址。預設空字串 = 首次開啟強制停在設定頁要使用者貼網址。
// 部署時填在這裡(或 -PexpoJsonUrl=... 覆寫):
//   .\gradlew.bat assembleRelease -PexpoJsonUrl=https://raw.githubusercontent.com/<帳號>/<repo>/main/data/exhibitions.json
// 放成建置期常數而不是寫死在 SettingsStore.kt,是為了讓本機測試用 localhost、
// 正式版用 GitHub 而不必改任何程式碼。
val expoJsonUrl: String = (findProperty("expoJsonUrl") as String?) ?: ""

android {
    namespace = "com.exporeminder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.exporeminder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DEFAULT_JSON_URL", "\"$expoJsonUrl\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig 是為了 BuildConfig.DEFAULT_JSON_URL(buildConfigField)
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    // android.jar 的 org.json 在單元測試裡只是沒有實作的 stub,要真的能解析得靠這個
    testImplementation("org.json:json:20240303")
}
