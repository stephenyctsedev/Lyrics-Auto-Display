import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 本地讀 local.properties，CI 讀環境變數。兩邊都唔會有密碼入 repo。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? =
    localProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.stephen.autolyrics"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stephen.autolyrics"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePathValue = secret("signing.storeFile", "SIGNING_STORE_FILE")
            if (storePathValue != null) {
                storeFile = file(storePathValue)
                storePassword = secret("signing.storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = secret("signing.keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = secret("signing.keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 只有喺 keystore 真係配置咗先簽，否則留返 unsigned（唔會靜靜跌返 debug key）
            signingConfig = if (secret("signing.storeFile", "SIGNING_STORE_FILE") != null)
                signingConfigs.getByName("release") else null
        }
        debug {
            // 明確用 Android 預設 debug key —— 同 release key 唔同
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.car.app)
    implementation(libs.car.app.projected)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    // Real org.json impl for plain-JVM unit tests: the Android SDK's org.json classes
    // in android.jar are stubs that throw unless mocked/Robolectric's runner is active.
    // This jar has the same package/class names, so LrclibSource's org.json.JSONObject
    // usage works unchanged both on-device (platform classes win at runtime) and in tests.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
