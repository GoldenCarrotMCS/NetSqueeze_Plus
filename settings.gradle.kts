pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
    }
}

rootProject.name = "NetSqueeze"

// 配套的 Fabric 客户端 mod
include("client")
