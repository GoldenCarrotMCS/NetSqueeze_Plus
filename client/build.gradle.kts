plugins {
    id("net.fabricmc.fabric-loom") version "1.15-SNAPSHOT"
}

group = "me.albert"
version = "1.0.0"

base {
    archivesName = "NetSqueeze-Client"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
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
