package me.albert.netsqueeze.netty

import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Test
import kotlin.test.*

class ChannelRegistryTest {
    @Test
    fun `closed connections immediately leave registry`() {
        val registry = ChannelRegistry()
        val channel = EmbeddedChannel()
        try {
            registry.track(channel)
            assertTrue(channel in registry.snapshot())
            channel.close()
            channel.runPendingTasks()
            assertTrue(registry.snapshot().isEmpty())
        } finally { channel.finishAndReleaseAll() }
    }

    @Test
    fun `forget detaches without closing vanilla connection`() {
        val registry = ChannelRegistry()
        val channel = EmbeddedChannel()
        try {
            registry.track(channel)
            registry.track(channel)
            registry.forget(channel)
            assertTrue(channel.isOpen)
            assertTrue(registry.snapshot().isEmpty())
        } finally { channel.finishAndReleaseAll() }
    }
}
