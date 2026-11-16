package dev.mcmesh.neoforge.mixin;

import dev.mcmesh.neoforge.MeshBoot;
import dev.mcmesh.neoforge.MeshOps;
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

    // vanilla only trusts ops.json: an operator with no entry there and no singleplayer owner slot (nulled above) falls
    // through to the integrated server's "commands not allowed for everyone means level 0" branch, so the world's own
    // operators ended up with no permissions at all. The mesh list decides instead.
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void mcmesh$meshOpLevel(com.mojang.authlib.GameProfile profile, CallbackInfoReturnable<Integer> cir) {
        if (!MeshOps.active() || !MeshOps.isOp(profile.getName())) {
            return;
        }
        // a name on the list runs the world; "everyone is an operator" only hands out the ordinary op commands
        cir.setReturnValue(MeshOps.isListed(profile.getName())
                ? 4
                : ((MinecraftServer) (Object) this).getOperatorUserPermissionLevel());
    }
}
