plugins {
    id("net.fabricmc.fabric-loom")
}

group = "me.albert"
version = "1.0.0"

base {
    archivesName = "NetSqueeze-Client"
}

// 和 Wurst 的 26.1.2 版本一致(26.x 起原版不混淆,用不带映射的 fabric-loom)
val minecraftVersion = "26.1.2"
val loaderVersion = "0.19.3"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}
