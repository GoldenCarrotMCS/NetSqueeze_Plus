plugins {
    id("net.fabricmc.fabric-loom")
}

group = "me.albert"
version = rootProject.version

base {
    archivesName = "NetSqueeze-Client"
}

// 和 Wurst 的 26.1.2 版本一致(26.x 起原版不混淆,用不带映射的 fabric-loom)
val minecraftVersion = providers.gradleProperty("minecraftVersion").getOrElse("26.1.2")
val loaderVersion = "0.19.3"

// 每个已声明客户端版本使用独立锁，避免兼容矩阵互相污染。
dependencyLocking { lockFile.set(layout.projectDirectory.file("gradle-$minecraftVersion.lockfile")) }

sourceSets.main { java.srcDir(rootProject.file("shared/src/main/java")) }

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    // NMS/Netty 由各自开发工具链提供；测试进程也需要完整开发类路径。
    testImplementation(files(configurations.compileClasspath))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

// 必须先运行根测试，确保对拍用的是本次提交生成的服务端数据。
tasks.test {
    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("test"))
    val fixtures = rootProject.layout.buildDirectory.dir("protocol-fixtures")
    inputs.dir(fixtures)
    systemProperty("netsqueeze.fixtures", fixtures.get().asFile.absolutePath)
}
