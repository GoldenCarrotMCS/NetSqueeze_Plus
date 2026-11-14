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
    implementation("com.github.luben:zstd-jni:1.5.7-26")
    include("com.github.luben:zstd-jni:1.5.7-26")
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

// TEST ONLY: reproduce older mod-provided classes lacking setLongMax in a fresh JVM.
// Production still embeds 1.5.7-26; do not resolve both in the same configuration.
val zstdLegacyRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies { add(zstdLegacyRuntime.name, "com.github.luben:zstd-jni:1.5.7-17") }

val zstdLegacyCompatibility by tasks.registering(Test::class) {
    description = "Run production decoding with legacy zstd-jni in an isolated JVM"
    group = "verification"
    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("test"), tasks.named("testClasses"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = zstdLegacyRuntime + sourceSets.test.get().runtimeClasspath.filter {
        !it.name.startsWith("zstd-jni-")
    }
    filter {
        includeTestsMatching("*DictionaryRoundTripTest")
        includeTestsMatching("*DictionaryHelloNegotiationTest")
        includeTestsMatching("*DictionaryProtocolTest")
        includeTestsMatching("*ZstdRuntimeCompatibilityTest")
        includeTestsMatching("*ZstdFrameDecoderTest")
        includeTestsMatching("*ZstdFrameHeaderTest")
        includeTestsMatching("*HybridProtocolRoundTripTest")
        includeTestsMatching("*ProtocolRoundTripTest")
    }
    val fixtures = rootProject.layout.buildDirectory.dir("protocol-fixtures")
    inputs.dir(fixtures)
    systemProperty("netsqueeze.fixtures", fixtures.get().asFile.absolutePath)
    systemProperty("netsqueeze.zstd.compat.version", "1.5.7-17")
}
tasks.check { dependsOn(zstdLegacyCompatibility) }
