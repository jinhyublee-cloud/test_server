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
    testOptions {
        unitTests {
            // 화면 테스트(Robolectric)용. 실제 앱(APK)에는 영향 없음
            isIncludeAndroidResources = true
            all {
                // 테스트 묶음(클래스)마다 새 JVM: 화면 테스트가 앞선 테스트의 상태에 영향받지 않게
                it.forkEvery = 1
                // 화면 테스트는 디버그 빌드에서만 (테스트용 화면이 디버그에만 들어 있음)
                if (it.name.contains("Release")) it.exclude("**/Fold*")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
            }
        }
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
    // 폴드 화면 크기별 스크린샷 테스트 (테스트 전용, APK에 들어가지 않음)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
