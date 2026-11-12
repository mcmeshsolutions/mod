package dev.mcmesh.neoforge.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldSelectionList.class)
public abstract class WorldListMixin {
    @Inject(method = "filterAccepts", at = @At("HEAD"), cancellable = true)
    private void mcmesh$hideShared(String filter, LevelSummary level, CallbackInfoReturnable<Boolean> cir) {
        if (level.getLevelId().startsWith("mesh-")) {
            cir.setReturnValue(false);
        }
    }
}
