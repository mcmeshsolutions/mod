package dev.mcmesh.neoforge.mixin;

import dev.mcmesh.neoforge.client.HostExit;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void mcmesh$warnGuests(CallbackInfo ci) {
        if (HostExit.intercept()) {
            ci.cancel();
        }
    }
}
