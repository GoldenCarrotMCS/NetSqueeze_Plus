package me.albert.netsqueeze.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.*

class SettingsTest {
    @Test
    fun `defaults and explicit settings load`() {
        assertEquals(Settings(), Settings.load(YamlConfiguration()))
        val config = YamlConfiguration()
        config.set("compression-level", 9)
        config.set("enabled", false)
        val settings = Settings.load(config)
        assertEquals(9, settings.compressionLevel)
        assertFalse(settings.enabled)
    }

    @Test
    fun `wrong YAML types are rejected instead of silently using defaults`() {
        val config = YamlConfiguration()
        config.set("compression-level", "fast")
        assertFailsWith<IllegalArgumentException> { Settings.load(config) }
        config.set("compression-level", 6)
        config.set("enabled", "yes")
        assertFailsWith<IllegalArgumentException> { Settings.load(config) }
    }

    @Test
    fun `proxy mode selection never forces raw compression through configured proxy`() {
        assertTrue(TransportMode.AUTO.useTunnel(true))
        assertTrue(TransportMode.AUTO.useTunnel(null))
        assertFalse(TransportMode.AUTO.useTunnel(false))
        assertTrue(TransportMode.VELOCITY.useTunnel(false))
        assertFailsWith<IllegalArgumentException> { TransportMode.DIRECT.useTunnel(true) }
        assertFailsWith<IllegalArgumentException> { TransportMode.parse("vc-plugin") }
        assertEquals(TransportMode.VELOCITY, TransportMode.parse("velocity"))
    }

    @Test
    fun `invalid compression and protocol bounds are rejected`() {
        assertFailsWith<IllegalArgumentException> { Settings(compressionLevel = -1) }
        assertFailsWith<IllegalArgumentException> { Settings(compressionLevel = 10) }
        assertFailsWith<IllegalArgumentException> { Settings(maxFrameRawBytes = 1023) }
        assertFailsWith<IllegalArgumentException> { Settings(maxFrameRawBytes = 8 * 1024 * 1024 + 1) }
    }
    @Test
    fun `zstd configuration rejects invalid size level and nonboolean switch`() {
        assertFailsWith<IllegalArgumentException> { Settings(zstdMinPacketBytes = 1023) }
        assertFailsWith<IllegalArgumentException> { Settings(zstdMinPacketBytes = 8388609) }
        assertFailsWith<IllegalArgumentException> { Settings(zstdLevel = 0) }
        assertFailsWith<IllegalArgumentException> { Settings(zstdLevel = 6) }
        val yaml = YamlConfiguration()
        yaml.set("zstd-enabled", "yes")
        assertFailsWith<IllegalArgumentException> { Settings.load(yaml) }
        yaml.set("zstd-enabled", false)
        assertFalse(Settings.load(yaml).zstdEnabled)
    }
}
