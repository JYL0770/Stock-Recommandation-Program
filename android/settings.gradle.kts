pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StockRecommender"

// 추천 엔진(순수 Kotlin) — Android SDK 없이도 빌드/테스트 가능
include(":core")

// Android 앱 — SDK가 있을 때만 포함 (CI / Android Studio)
val localProps = file("local.properties")
val hasSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))
if (hasSdk) include(":app") else println("Android SDK 없음 → :core 만 빌드합니다")
