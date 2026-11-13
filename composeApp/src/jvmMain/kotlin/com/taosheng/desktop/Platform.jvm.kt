package com.taosheng.desktop

actual fun platformName(): String =
    "${System.getProperty("os.name")} (JVM ${System.getProperty("java.version")})"