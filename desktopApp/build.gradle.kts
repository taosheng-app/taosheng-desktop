import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // 桌面端 = JVM 目标；Windows / macOS / Linux 共用同一份 desktopMain 代码
    jvm("desktop")
    jvmToolchain(21)

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":ui"))
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutinesSwing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.resound.desktop.MainKt"

        nativeDistributions {
            // 各平台只能在对应系统上打包：Windows→Msi，macOS→Dmg，Linux→Deb
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TaoshengDesktop"
            packageVersion = "1.0.0"
            description = "涛声桌面版"
            vendor = "Taosheng"

            macOS {
                bundleID = "app.resound.desktop"
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