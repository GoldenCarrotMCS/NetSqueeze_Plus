package me.albert.netsqueeze.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.HandlerNames;

/**
 * 挂在 splitter 前面(入站先经过它),两个阶段:
 * <ul>
 *   <li><b>切换前</b>:按原版的 varint 帧长自己切帧,一次只交给 splitter 一个完整帧(连长度前缀原样)。
 *       这样 splitter 手里永远不会攒着后面的字节,enable 那一帧交出去、{@link EnableDetector} 同步置位后,
 *       剩下的字节还在这里,直接按新格式解,不用从 splitter 里往回抢,也不用在别的解码器中途改管线。</li>
 *   <li><b>切换后</b>:帧格式和服务端 FrameDeflater 一致,varint 帧长 + deflate 输出(整条连接共用一个流、
 *       每帧 SYNC_FLUSH);解出来是一串"varint 包长 + 包字节",逐包从 decompress(没有就 splitter)的 context
 *       往下发,绕过 splitter 和原版逐包解压,直接进 decoder。</li>
 * </ul>
 * 服务端不认握手时,配置阶段结束由 EnableDetector 通知撤掉自己,之后零开销。全部在这条连接的 netty 线程上跑。
 */
final class StreamDecoder extends ChannelInboundHandlerAdapter {

    static final String NAME = "netsqueeze_inflate";

    /** 单帧压缩后上限;原版帧最大 2 MB(3 字节 varint),流式帧一 tick 一帧,正常远小于这个 */
    private static final int MAX_FRAME = 16 * 1024 * 1024;

    /** 单帧解压后上限,防坏流把内存撑爆 */
    private static final int MAX_INFLATED = 64 * 1024 * 1024;

    /** 每次 inflate 前至少留出这么多输出空间 */
    private static final int CHUNK = 8 * 1024;

    /** nowrap:服务端帧里不带 zlib 头和校验和 */
    private final Inflater inflater = new Inflater(true);

    private ByteBuf cumulation;
    private boolean streaming;
    private boolean enablePending;
    private boolean giveUpPending;
    private boolean removed;

    /** EnableDetector 在本线程同步调,等交出去的那一帧返回后才生效 */
    void enable() {
        this.enablePending = true;
    }

    void giveUp() {
        this.giveUpPending = true;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (!(msg instanceof ByteBuf in)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (this.cumulation == null) this.cumulation = ctx.alloc().buffer(in.readableBytes());
        this.cumulation.writeBytes(in);
        in.release();
        try {
            this.process(ctx);
        } catch (final DataFormatException e) {
            throw new DecoderException("NetSqueeze 解压失败", e);
        } finally {
            this.compact();
        }
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        // 原生内存,连接关闭(netty 拆管线)或撤掉时都会走这里
        this.inflater.end();
        if (this.cumulation != null) {
            this.cumulation.release();
            this.cumulation = null;
        }
    }

    private void process(final ChannelHandlerContext ctx) throws DataFormatException {
        while (!this.removed && this.cumulation != null && this.cumulation.isReadable()) {
            final int start = this.cumulation.readerIndex();
            final int length = readVarInt(this.cumulation);
            if (length < 0 || this.cumulation.readableBytes() < length) {
                this.cumulation.readerIndex(start);
                return;
            }
            if (length > MAX_FRAME) throw new CorruptedFrameException("NetSqueeze 帧过大: " + length);

            if (this.streaming) {
                this.inflate(ctx, this.cumulation.readSlice(length));
                continue;
            }

            // 原版帧连长度前缀原样交给 splitter;拷一份,cumulation 之后要压缩回收,不能把切片交出去
            final int end = this.cumulation.readerIndex() + length;
            this.cumulation.readerIndex(start);
            ctx.fireChannelRead(this.cumulation.readBytes(end - start));
            if (this.enablePending) {
                this.enablePending = false;
                this.streaming = true;
            }
            if (this.giveUpPending && !this.streaming) {
                this.removeSelf(ctx);
                return;
            }
        }
    }

    private void inflate(final ChannelHandlerContext ctx, final ByteBuf frame) throws DataFormatException {
        this.inflater.setInput(frame.nioBuffer());
        final ByteBuf out = ctx.alloc().directBuffer(Math.max(CHUNK, frame.readableBytes() * 4));
        try {
            // 输出没填满且输入已吃完,这一帧就全出来了(SYNC_FLUSH 保证);填满了说明可能还有,扩容接着要
            while (true) {
                out.ensureWritable(CHUNK);
                final int space = out.writableBytes();
                final int produced = this.inflater.inflate(out.internalNioBuffer(out.writerIndex(), space));
                out.writerIndex(out.writerIndex() + produced);
                if (out.readableBytes() > MAX_INFLATED) throw new CorruptedFrameException("NetSqueeze 帧解压后过大");
                if (this.inflater.finished() || this.inflater.needsDictionary()) throw new CorruptedFrameException("NetSqueeze 压缩流异常结束");
                if (produced < space && this.inflater.needsInput()) break;
                if (produced == 0) throw new CorruptedFrameException("NetSqueeze 压缩流卡住");
            }

            final ChannelHandlerContext target = packetTarget(ctx);
            while (out.isReadable()) {
                final int length = readVarInt(out);
                if (length < 0 || length > out.readableBytes()) throw new CorruptedFrameException("NetSqueeze 帧里有半个包");
                if (length > CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH) throw new CorruptedFrameException("NetSqueeze 包过大: " + length);
                target.fireChannelRead(out.readRetainedSlice(length));
            }
        } finally {
            out.release();
        }
    }

    /** 包要直接进 decoder:从 decompress 的 context 往下发就跳过了它和 splitter;没开压缩时没有 decompress */
    private static ChannelHandlerContext packetTarget(final ChannelHandlerContext ctx) {
        final ChannelHandlerContext decompress = ctx.pipeline().context(HandlerNames.DECOMPRESS);
        if (decompress != null) return decompress;
        final ChannelHandlerContext splitter = ctx.pipeline().context(HandlerNames.SPLITTER);
        return splitter != null ? splitter : ctx;
    }

    /** 服务端不认握手:剩下的字节(可能是半帧)交还 splitter,自己摘掉 */
    private void removeSelf(final ChannelHandlerContext ctx) {
        this.removed = true;
        final ByteBuf rest = this.cumulation;
        this.cumulation = null;
        ctx.pipeline().remove(this);
        if (rest.isReadable()) {
            ctx.fireChannelRead(rest);
            return;
        }
        rest.release();
    }

    private void compact() {
        if (this.cumulation == null) return;
        if (!this.cumulation.isReadable()) {
            this.cumulation.release();
            this.cumulation = null;
            return;
        }
        this.cumulation.discardSomeReadBytes();
    }

    /** 读一个 varint;字节不够返回 -1(读指针位置由调用方复原) */
    private static int readVarInt(final ByteBuf buf) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (!buf.isReadable()) return -1;
            final byte b = buf.readByte();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new CorruptedFrameException("NetSqueeze varint 过长");
    }
}
