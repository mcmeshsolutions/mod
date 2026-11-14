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
        long limit = 0;
        var rules = dev.mcmesh.neoforge.CompatRegistry.rules();
        if (rules.hasTickLimits()) {
            var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            limit = key == null ? 0 : rules.maxTickNanos(key.getNamespace());
        }
        long started = limit > 0 ? System.nanoTime() : 0;
        try {
            original.call();
        } catch (Throwable t) {
            Quarantine.freeze(this, "block entity " + net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()) + " at " + blockEntity.getBlockPos(), t, blockEntity.getLevel(),
                    () -> blockEntity.saveWithFullMetadata(blockEntity.getLevel().registryAccess()));
            return;
        }
        if (limit > 0 && System.nanoTime() - started > limit) {
            Quarantine.freeze(this, "block entity " + net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()) + " at " + blockEntity.getBlockPos(),
                    new IllegalStateException("one tick took " + (System.nanoTime() - started) / 1000 + " us, the mod's compat manifest allows " + limit / 1000), blockEntity.getLevel(),
                    () -> blockEntity.saveWithFullMetadata(blockEntity.getLevel().registryAccess()));
        }
    }
}
