plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm("desktop")
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
        }
        val desktopMain by getting {
            dependencies {
                api(project(":base"))
                implementation(libs.java.keyring)
                implementation(libs.org.json)
            }
        }
    }
}
