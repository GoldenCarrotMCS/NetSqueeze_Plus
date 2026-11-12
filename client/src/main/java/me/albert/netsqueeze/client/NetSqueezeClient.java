package me.albert.netsqueeze.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import me.albert.netsqueeze.client.mixin.ConnectionAccessor;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 和服务端插件(NetSqueeze 的 stream 包)的握手约定,两边改协议时一起改 {@link #VERSION}:
 * <ol>
 *   <li>进配置阶段时发 {@code netsqueeze:hello_v<版本>},不带数据(原版 DiscardedPayload 不带数据,版本只能编进频道名,
 *       这样不用依赖 Fabric API 注册载荷类型);</li>
 *   <li>服务端认得就回 {@code netsqueeze:enable},这是最后一个原版格式的包,之后全是流式压缩的帧,
 *       由 {@link StreamDecoder} 在 netty 线程上当场切换解码;服务端不认得就什么都不回,配置阶段结束时撤掉解码器。</li>
 * </ol>
 * 上行不动,照走原版。
 */
public final class NetSqueezeClient {

    public static final Logger LOGGER = LoggerFactory.getLogger("NetSqueeze");

    static final int VERSION = 3;

    static final Identifier HELLO = Identifier.fromNamespaceAndPath("netsqueeze", "hello_v" + VERSION);

    static final Identifier HELLO_ZSTD = Identifier.fromNamespaceAndPath("netsqueeze", "hello_v" + VERSION + "_zstd");

    static final Identifier ENABLE_ZSTD = Identifier.fromNamespaceAndPath("netsqueeze", "enable_v" + VERSION + "_zstd");

    static final Identifier LEGACY_ENABLE = Identifier.fromNamespaceAndPath("netsqueeze", "enable");
    static final Identifier LEGACY_HELLO = Identifier.fromNamespaceAndPath("netsqueeze", "hello_v1");
    static final Identifier ENABLE = Identifier.fromNamespaceAndPath("netsqueeze", "enable_v" + VERSION);

    private NetSqueezeClient() {
    }

    /** 连接刚建好、还没收到任何字节时调:解码器挂在 splitter 前面,检测器挂在 packet_handler 前面 */
    public static void install(final ChannelPipeline pipeline) {
        if (pipeline.get(HandlerNames.SPLITTER) == null || pipeline.get(HandlerNames.PACKET_HANDLER) == null) return;
        pipeline.addBefore(HandlerNames.SPLITTER, StreamDecoder.NAME, new StreamDecoder());
        pipeline.addBefore(HandlerNames.PACKET_HANDLER, EnableDetector.NAME, new EnableDetector());
    }

    /**
     * 正版服登录时原版把 decrypt 插在 splitter 前面,正好夹在解码器和 splitter 中间,解码器就会看到密文,得挪到 decrypt 后面。
     * 这时服务端在等加密回应,不会有新字节进来,换个新实例不丢东西(GoldenCarrot服务器的正版/代理链路需要单独验收)
     */
    public static void moveAfterDecrypt(final ChannelPipeline pipeline) {
        if (pipeline.get(HandlerNames.DECRYPT) == null || pipeline.get(StreamDecoder.NAME) == null) return;
        pipeline.remove(StreamDecoder.NAME);
        pipeline.addAfter(HandlerNames.DECRYPT, StreamDecoder.NAME, new StreamDecoder());
    }

    /** 新协议建立后重新放在 bundler 前；原版会把 bundler 插到 decoder 的紧后方。 */
    public static void installTunnel(final Channel channel) {
        if (channel == null) return;
        Runnable install = () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HandlerNames.DECODER) == null) return;
            if (pipeline.get(TunnelClientHandler.NAME) != null) pipeline.remove(TunnelClientHandler.NAME);
            pipeline.addAfter(HandlerNames.DECODER, TunnelClientHandler.NAME, new TunnelClientHandler());
        };
        if (channel.eventLoop().inEventLoop()) install.run();
        else channel.eventLoop().execute(install);
    }

    /** 登录完成、出站已切到配置协议后调;解码器没挂上(内存连接等)就不发,否则服务端一切换这边就解不了 */
    public static void sendHello(final Connection connection) {
        final Channel channel = ((ConnectionAccessor) connection).netsqueeze$getChannel();
        if (channel == null || channel.pipeline().get(StreamDecoder.NAME) == null) return;
        connection.send(new ServerboundCustomPayloadPacket(new DiscardedPayload(ZstdSupport.available() ? HELLO_ZSTD : HELLO)));
        // Old servers ignore the first hello and negotiate their unchanged v1 format.
        connection.send(new ServerboundCustomPayloadPacket(new DiscardedPayload(LEGACY_HELLO)));
    }
}
