plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mydaylog.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mydaylog.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 10
        versionName = "2.7"
    }

    // 서명은 빌드 밖에서 함 (sign-release.sh). 키와 비밀번호는 저장소에 두지 않음 (S-01)
    buildTypes {
        debug {
            // 실제 앱과 다른 패키지·디버그 키 → 실제 앱 데이터에 접근 불가 (S-02)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // 기기 내장 Gemini Nano (AICore). 추론은 폰 안에서만 실행됨. 인터넷 권한은 매니페스트에서 제거
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
