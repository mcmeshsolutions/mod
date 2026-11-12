package dev.mcmesh.neoforge.mixin;

import dev.mcmesh.neoforge.MeshBoot;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    // the gateway has already proven who guests are, so the host must not run its own Mojang check
    @Inject(method = "usesAuthentication", at = @At("HEAD"), cancellable = true)
    private void mcmesh$noLocalAuth(CallbackInfoReturnable<Boolean> cir) {
        if (MeshBoot.held() != null) {
            cir.setReturnValue(false);
        }
    }

    // without this the host's data lives in level.dat under a Mojang UUID instead of playerdata under the shared offline UUID
    @Inject(method = "getSingleplayerProfile", at = @At("HEAD"), cancellable = true)
    private void mcmesh$noOwnerSlot(CallbackInfoReturnable<com.mojang.authlib.GameProfile> cir) {
        if (MeshBoot.held() != null) {
            cir.setReturnValue(null);
        }
    }
}
