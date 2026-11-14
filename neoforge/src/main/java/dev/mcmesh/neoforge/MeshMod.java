package dev.mcmesh.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

@Mod("mcmesh")
public final class MeshMod {
    public MeshMod(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, MeshConfig.SPEC);
        bus.addListener((ModConfigEvent e) -> {
            if (e.getConfig().getSpec() == MeshConfig.SPEC) {
                dev.mcmesh.core.GatewayClient.pack = MeshConfig.PACK_ID.get();
                dev.mcmesh.core.GatewayClient.packVersion = MeshConfig.packVersion();
            }
        });
        // the listing asks the gateway "could I take over?" with this score, long before any hosting has loaded MeshService;
        // without it the score falls back to the core count and a strong machine looks weaker than the host
        dev.mcmesh.core.GatewayClient.hwScore = HostBench::bidScore;
        LogShipper.start();
    }
}
