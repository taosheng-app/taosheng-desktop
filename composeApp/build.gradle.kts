import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // 桌面端 = JVM 目标；Windows / macOS / Linux 共用同一份 jvmMain 代码
    jvm()

    // 统一 JDK 版本（jpackage 打包需要 17+）
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            // currentOs 会按构建机器自动选择对应平台的 Skia 原生库
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.taosheng.desktop.MainKt"

        nativeDistributions {
            // 各平台只能在对应系统上打包：Windows→Msi，macOS→Dmg，Linux→Deb
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TaoshengDesktop"
            packageVersion = "1.0.0"
            description = "Taosheng Desktop - Kotlin Multiplatform Hello World"
            vendor = "Taosheng"

            macOS {
                bundleID = "com.taosheng.desktop"
            }
            windows {
                menuGroup = "Taosheng"
                // 固定的 UUID，保证后续版本升级可覆盖安装
                upgradeUuid = "6f1d0c7e-3b0a-4f6e-9d63-5a1c2e8b7a41"
            }
            linux {
                shortcut = true
            }
        }
    }
}