package me.albert.netsqueeze.client.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import javax.crypto.Cipher;
import me.albert.netsqueeze.client.NetSqueezeClient;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Shadow
    private Channel channel;

    @Shadow
    public abstract PacketFlow getReceiving();

    /** 连接刚建好(splitter、packet_handler 都在,还没收任何字节)时挂上解码器;只管客户端这头,内存连接没有 splitter 会跳过 */
    @Inject(method = "configurePacketHandler", at = @At("TAIL"))
    private void netsqueeze$install(final ChannelPipeline pipeline, final CallbackInfo ci) {
        if (this.getReceiving() != PacketFlow.CLIENTBOUND) return;
        NetSqueezeClient.install(pipeline);
    }

    @Inject(method = "setEncryptionKey", at = @At("TAIL"))
    private void netsqueeze$afterEncryption(final Cipher decryptCipher, final Cipher encryptCipher, final CallbackInfo ci) {
        if (this.getReceiving() != PacketFlow.CLIENTBOUND) return;
        NetSqueezeClient.moveAfterDecrypt(this.channel.pipeline());
    }
}
