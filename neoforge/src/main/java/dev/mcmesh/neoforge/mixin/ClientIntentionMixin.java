package dev.mcmesh.neoforge.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.mcmesh.neoforge.MeshTap;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientIntentionPacket.class)
public abstract class ClientIntentionMixin {
    @ModifyVariable(method = "<init>(ILjava/lang/String;ILnet/minecraft/network/protocol/handshake/ClientIntent;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String mcmesh$ticket(String host, @Local(argsOnly = true) ClientIntent intent) {
        return MeshTap.handshakeHost(host, intent.name());
    }
}
