package me.albert.netsqueeze.client.mixin;

import me.albert.netsqueeze.client.TunnelPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class CustomPayloadCodecMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"), index = 0, require = 2)
    private static <B extends FriendlyByteBuf> CustomPacketPayload.FallbackProvider<B> netsqueeze$payload(
            CustomPacketPayload.FallbackProvider<B> original) {
        return TunnelPayload.fallback(original);
    }
}
