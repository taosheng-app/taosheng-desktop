plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvm("desktop")
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(project(":base"))
            api(project(":platform"))
            api(libs.androidx.roomRuntime)
            api(libs.androidx.sqliteBundled)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.onnxruntime)
                implementation(libs.okhttp)
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

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspDesktop", libs.androidx.roomCompiler)
}