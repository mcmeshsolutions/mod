package dev.mcmesh.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod("mcmesh")
public final class MeshMod {
    public MeshMod(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, MeshConfig.SPEC);
        LogShipper.start();
    }
}
