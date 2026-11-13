package me.albert.netsqueeze.client.mixin;

import me.albert.netsqueeze.client.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Local-only command. Cancel before any server command packet can be created. */
@Mixin(ClientPacketListener.class)
public abstract class ClientCacheCommandMixin {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void netsqueeze$cacheCommand(String command, CallbackInfo ci) {
        if (ClientChunkCache.command(command)) ci.cancel();
    }
}
