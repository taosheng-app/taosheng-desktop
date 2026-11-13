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

            }
            dependencies {

                implementation(libs.java.keyring)
                implementation(libs.org.json)
            }
        }
        val desktopTest by getting {
            dependencies {

                implementation(libs.kotlin.testJunit)
                implementation(libs.kotlinx.coroutinesTest)
            }
        }
    }
}