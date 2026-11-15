package dev.mcmesh.neoforge.mixin;

import java.util.Collections;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla places a tree's bee nest with Collections.shuffle(list), which draws from an unseeded java.util.Random, so the same seed
 * gives a nest in another place, or none, every time a chunk is generated. That is the only unseeded draw in the world generation
 * code (board/RESEARCH.md 1h) and it is what stops two machines from agreeing on a tile: with it seeded by the feature's own
 * random source, which every other draw of the tree already uses, the result no longer depends on the run.
 */
@Mixin(BeehiveDecorator.class)
public abstract class BeehiveDecoratorMixin {
    @Unique
    private static final ThreadLocal<TreeDecorator.Context> mcmesh$context = new ThreadLocal<>();

    @Inject(method = "place", at = @At("HEAD"))
    private void mcmesh$remember(TreeDecorator.Context context, CallbackInfo ci) {
        mcmesh$context.set(context);
    }

    @Redirect(method = "place", at = @At(value = "INVOKE", target = "Ljava/util/Collections;shuffle(Ljava/util/List;)V"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mcmesh$seededShuffle(List list) {
        TreeDecorator.Context c = mcmesh$context.get();
        if (c == null) {
            Collections.shuffle(list);
        } else {
            Util.shuffle(list, c.random());
        }
    }
}
