package me.albert.netsqueeze.platform

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.*

/** 用同一个发布 Jar（包括重定位 Kotlin）在目标 dev bundle 上解析，不靠重新编译冒充二进制兼容。 */
@EnabledIfSystemProperty(named = "netsqueeze.adapterJar", matches = ".+")
class PublishedAdapterTest {
    private class PluginLoader(url: URL, parent: ClassLoader) : URLClassLoader(arrayOf(url), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (!name.startsWith("me.albert.netsqueeze.")) return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(loaded)
            loaded
        }
    }

    @Test
    fun `the actual published adapter links against this target runtime`() {
        val jar = File(System.getProperty("netsqueeze.adapterJar"))
        assertTrue(jar.isFile, "Expected exact baseline build artifact")
        PluginLoader(jar.toURI().toURL(), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("me.albert.netsqueeze.platform.ServerNetwork")
            val instance = type.getField("INSTANCE").get(null)
            val verify = type.methods.single { it.name.startsWith("verifyApi") && it.parameterCount == 0 }
            assertTrue((verify.invoke(instance) as Collection<*>).isNotEmpty())
            val protocol = type.methods.single { it.name.startsWith("protocolVersion") && it.parameterCount == 0 }
            assertTrue((protocol.invoke(instance) as Number).toInt() > 0)
        }
    }
}
