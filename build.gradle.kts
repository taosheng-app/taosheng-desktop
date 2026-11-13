plugins {
    // 根项目只声明插件版本（apply false），具体模块各自启用，避免类加载器冲突
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}