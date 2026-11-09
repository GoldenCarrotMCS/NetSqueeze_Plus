import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "9.4.1"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    // client 子项目用;插件统一在根上声明,免得和 paperweight 分在两个类加载器里打架
    id("net.fabricmc.fabric-loom") version "1.15-SNAPSHOT" apply false
}

group = "me.albert"
version = "1.1.0"

repositories {
    mavenLocal()
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

dependencies {
    paperweight.foliaDevBundle("26.1.2.build.8-stable")
    // 公开版不依赖别的插件提供 Kotlin 运行库,打进 jar
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

tasks.shadowJar {
    // 改包名,和别的插件各自带的 Kotlin 版本互不干扰
    relocate("kotlin", "me.albert.netsqueeze.libs.kotlin")
    // 只留插件代码用得到的 Kotlin 类(没用反射,不会误删),jar 从近 2 MB 降下来
    minimize()
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
