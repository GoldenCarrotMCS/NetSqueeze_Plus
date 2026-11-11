package me.albert.netsqueeze.client.mixin;

import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PacketDecoder.class)
public interface PacketDecoderAccessor {
    @Accessor("protocolInfo") ProtocolInfo<?> netsqueeze$getProtocolInfo();
}
