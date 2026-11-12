package dev.mcmesh.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import dev.mcmesh.neoforge.MeshOps;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
    private void mcmesh$ops(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
        if (MeshOps.active()) {
            cir.setReturnValue(MeshOps.isOp(profile.getName()));
        }
    }

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void mcmesh$whitelist(java.net.SocketAddress addr, GameProfile profile, CallbackInfoReturnable<net.minecraft.network.chat.Component> cir) {
        if (dev.mcmesh.neoforge.MeshWhitelist.active() && !MeshOps.isOp(profile.getName())
                && !dev.mcmesh.neoforge.MeshWhitelist.allows(profile.getName())) {
            cir.setReturnValue(net.minecraft.network.chat.Component.literal("You are not on this world's whitelist."));
        }
    }
}
