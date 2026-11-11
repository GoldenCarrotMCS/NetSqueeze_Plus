package me.albert.netsqueeze.stream

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import me.albert.netsqueeze.stats.StreamStats
import net.minecraft.network.HandlerNames
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.*

class StreamCompressorTest {
    private fun channel(level: Int = 6, limit: Int = 8 * 1024 * 1024) = EmbeddedChannel().also {
        it.pipeline().addLast(HandlerNames.PREPENDER, ChannelOutboundHandlerAdapter())
        it.pipeline().addLast("netsqueeze_stream", StreamCompressor(level, limit))
    }

    @Test
    fun `actual compressor writes cross-platform fixtures at three levels`() {
        val dir = Path.of(System.getProperty("netsqueeze.fixtures"))
        Files.createDirectories(dir)
        val random = Random(0x5eed)
        val large = ByteArray(3 * 1024 * 1024).also(random::nextBytes)
        val repeated = ByteArray(1024) { (it % 31).toByte() }
        val packets = List(50) { index ->
            if (index % 3 == 0) repeated.copyOf() else ByteArray(index * 17 + 1).also(random::nextBytes)
        } + listOf(large, repeated, repeated)
        DataOutputStream(Files.newOutputStream(dir.resolve("expected.bin"))).use { out ->
            out.writeInt(packets.size)
            packets.forEach { out.writeInt(it.size); out.write(it) }
        }
        for (level in listOf(0, 6, 9)) {
            val ch = channel(level, 4096)
            try {
                ch.flushOutbound() // 空 flush 不能生成帧
                assertNull(ch.readOutbound<Any>())
                val promises = packets.mapIndexed { index, bytes ->
                    val future = ch.write(Unpooled.wrappedBuffer(bytes))
                    if (index % 7 == 0) ch.flushOutbound()
                    future
                }
                ch.flushOutbound()
                promises.forEach { assertTrue(it.isSuccess, "packet promise must complete on frame write") }
                Files.newOutputStream(dir.resolve("level-$level.bin")).use { out ->
                    while (true) {
                        val frame = ch.readOutbound<ByteBuf>() ?: break
                        try {
                            val bytes = ByteArray(frame.readableBytes())
                            frame.readBytes(bytes)
                            out.write(bytes)
                        } finally { frame.release() }
                    }
                }
            } finally { ch.finishAndReleaseAll() }
        }
    }

    @Test
    fun `promises wait for flush and buffers are released`() {
        val initial = StreamStats.active.get()
        val ch = channel()
        try {
            val input = Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3))
            val promise = ch.write(input)
            assertEquals(0, input.refCnt())
            assertFalse(promise.isDone)
            ch.flushOutbound()
            assertTrue(promise.isSuccess)
            assertNotNull(ch.readOutbound<ByteBuf>()).release()
        } finally { ch.finishAndReleaseAll() }
        assertEquals(initial, StreamStats.active.get())
    }

    @Test
    fun `removal fails unflushed promises and releases native state`() {
        val ch = channel()
        try {
            val future = ch.write(Unpooled.wrappedBuffer(byteArrayOf(7)))
            ch.pipeline().remove("netsqueeze_stream")
            assertTrue(future.isDone)
            assertFalse(future.isSuccess)
        } finally { ch.finishAndReleaseAll() }
    }

    @Test
    fun `asynchronous downstream failure fails original promises and closes stream`() {
        val before = StreamStats.failures.sum()
        val log = StreamStats.logFirstFailure
        StreamStats.logFirstFailure = false // 本测试不构造真实 Bukkit 插件 logger。
        val ch = EmbeddedChannel()
        try {
            ch.pipeline().addLast("failed_socket", object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    ReferenceCountUtil.release(msg)
                    ctx.executor().execute { promise.tryFailure(IllegalStateException("test write failure")) }
                }
            })
            ch.pipeline().addLast(HandlerNames.PREPENDER, ChannelOutboundHandlerAdapter())
            ch.pipeline().addLast("netsqueeze_stream", StreamCompressor())
            val promise = ch.write(Unpooled.wrappedBuffer(byteArrayOf(1)))
            ch.flushOutbound()
            ch.runPendingTasks()
            assertTrue(promise.isDone)
            assertFalse(promise.isSuccess)
            assertFalse(ch.isOpen)
            assertEquals(before + 1, StreamStats.failures.sum())
        } finally {
            ch.finishAndReleaseAll()
            StreamStats.logFirstFailure = log
        }
    }

    @Test
    fun `configuration tasks preserve ordering`() {
        val ch = channel()
        try {
            val marker = Any()
            ch.write(Unpooled.wrappedBuffer(byteArrayOf(1)))
            ch.write(marker)
            ch.flushOutbound()
            assertNotNull(ch.readOutbound<ByteBuf>()).release()
            assertSame(marker, ch.readOutbound<Any>())
        } finally { ch.finishAndReleaseAll() }
    }
}
