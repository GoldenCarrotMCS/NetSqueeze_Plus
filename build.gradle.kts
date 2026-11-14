import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "9.4.1"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    // client 子项目用;插件统一在根上声明,免得和 paperweight 分在两个类加载器里打架
    id("net.fabricmc.fabric-loom") version "1.15.5" apply false
}

group = "me.albert"
version = "1.6.0"

sourceSets.main { java.srcDir("shared/src/main/java") }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc-repo" }
}

java {
    targetCompatibility = JavaVersion.VERSION_25
    sourceCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget = JvmTarget.JVM_25 }
}

// CI pins both platform and bundle instead of resolving a changing + version.
val serverPlatform = providers.gradleProperty("serverPlatform").getOrElse("folia")
val serverBundle = providers.gradleProperty("serverBundle").getOrElse("26.1.2.build.8-stable")
dependencyLocking { lockFile.set(layout.projectDirectory.file("gradle-$serverPlatform-$serverBundle.lockfile")) }

dependencies {
    when (serverPlatform) {
        "paper" -> paperweight.paperDevBundle(serverBundle)
        "folia" -> paperweight.foliaDevBundle(serverBundle)
        else -> error("serverPlatform must be paper or folia")
    }
    // 公开版不依赖别的插件提供 Kotlin 运行库,打进 jar
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("com.github.luben:zstd-jni:1.5.7-26")
    testImplementation(kotlin("test-junit5"))
    // NMS/Netty 由各自开发工具链提供；测试进程也需要完整开发类路径。
    testImplementation(files(configurations.compileClasspath))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.shadowJar {
    // 改包名,和别的插件各自带的 Kotlin 版本互不干扰
    relocate("kotlin", "me.albert.netsqueeze.libs.kotlin")
    // 保留完整 Kotlin 运行库，避免静态裁剪遗漏运行时的间接引用。
    exclude("META-INF/*.kotlin_module", "META-INF/versions/**")
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}

// 根测试生成真实服务端压缩器的线格式夹具，客户端测试消费它，不引入 Minecraft 两端类路径冲突。
val protocolFixtures = layout.buildDirectory.dir("protocol-fixtures")
tasks.test {
    useJUnitPlatform()
    systemProperty("netsqueeze.fixtures", protocolFixtures.get().asFile.absolutePath)
    providers.gradleProperty("adapterJar").orNull?.let { path ->
        systemProperty("netsqueeze.adapterJar", file(path).absolutePath)
        inputs.file(file(path))
    }
    outputs.dir(protocolFixtures)
}

allprojects {
    dependencyLocking { lockAllConfigurations() }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
