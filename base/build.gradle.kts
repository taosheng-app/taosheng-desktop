plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm("desktop")
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
                api(libs.org.json)
            api(libs.kotlinx.coroutinesCore)
        }
    }
}