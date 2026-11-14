package dev.mcmesh.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mcmesh.neoforge.Quarantine;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
public abstract class EntityTickMixin {
    @WrapMethod(method = "guardEntityTick")
    private <T extends Entity> void mcmesh$quarantine(Consumer<T> ticker, T entity, Operation<Void> original) {
        if (!Quarantine.hosting() || entity instanceof Player) {
            original.call(ticker, entity);
            return;
        }
        if (Quarantine.frozen(entity)) {
            return;
        }
        long limit = 0;
        var rules = dev.mcmesh.neoforge.CompatRegistry.rules();
        if (rules.hasTickLimits()) {
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            limit = key == null ? 0 : rules.maxTickNanos(key.getNamespace());
        }
        long started = limit > 0 ? System.nanoTime() : 0;
        try {
            original.call(ticker, entity);
            if (limit > 0 && System.nanoTime() - started > limit) {
                throw new IllegalStateException("one tick took " + (System.nanoTime() - started) / 1000 + " us, the mod's compat manifest allows " + limit / 1000);
            }
        } catch (Throwable t) {
            Quarantine.freeze(entity, "entity " + entity.getType() + " at " + entity.blockPosition(), t, entity.level(), () -> {
                CompoundTag tag = new CompoundTag();
                entity.saveWithoutId(tag);
                return tag;
            });
        }
    }
}
