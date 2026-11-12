package dev.mcmesh.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mcmesh.neoforge.Quarantine;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickMixin {
    @Shadow
    @Final
    private BlockEntity blockEntity;

    @WrapMethod(method = "tick")
    private void mcmesh$quarantine(Operation<Void> original) {
        if (!Quarantine.hosting()) {
            original.call();
            return;
        }
        if (Quarantine.frozen(this)) {
            return;
        }
        try {
            original.call();
        } catch (Throwable t) {
            Quarantine.freeze(this, "block entity " + blockEntity.getType() + " at " + blockEntity.getBlockPos(), t);
        }
    }
}
