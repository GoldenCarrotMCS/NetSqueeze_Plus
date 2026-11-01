package me.albert.netsqueeze.client.mixin;

import me.albert.netsqueeze.client.NetSqueezeClient;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {

    @Shadow
    @Final
    private Connection connection;

    /** 原版在这里切到配置协议并发 brand,TAIL 时出站已是配置协议,紧跟着发 hello */
    @Inject(method = "handleLoginFinished", at = @At("TAIL"))
    private void netsqueeze$hello(final ClientboundLoginFinishedPacket packet, final CallbackInfo ci) {
        NetSqueezeClient.sendHello(this.connection);
    }
}
