import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        androidStudio("2024.1.1.13")   // Android Studio Koala
        bundledPlugin("org.jetbrains.android")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.perfmonitor"
        name = "Perf Monitor"
        version = "0.1.0"
    }
}