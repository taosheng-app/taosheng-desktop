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
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(project(":base"))
                implementation(libs.java.keyring)
                implementation(libs.org.json)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(project(":base"))
                implementation(libs.kotlin.testJunit)
                implementation(libs.kotlinx.coroutinesTest)
            }
        }
    }
}
