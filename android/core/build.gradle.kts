import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    // 실제 네이버 시세 연동 테스트는 NETWORK_TESTS=1 일 때만 (CI)
    environment("NETWORK_TESTS", System.getenv("NETWORK_TESTS") ?: "0")
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// 전체 시장 스캔: ./gradlew :core:scan --args="KR build/scan/scan_kr.json"
tasks.register<JavaExec>("scan") {
    group = "application"
    mainClass.set("com.stockrec.core.MarketScanKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx2g")
}
