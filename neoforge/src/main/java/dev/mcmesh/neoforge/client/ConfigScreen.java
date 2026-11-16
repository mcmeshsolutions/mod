package dev.mcmesh.neoforge.client;

import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** The settings screen: NeoForge builds it from the config spec, the names and hints come from the lang file. Reached from the Mods list and the title screen. */
public final class ConfigScreen {
    /** Only a dedicated server ever acts on these, and the screen exists on the game client alone, so they are left out of it. */
    private static final Set<String> SERVER_ONLY =
            Set.of("anchorStandby", "anchorWakeOnJoin", "anchorIdleMinutes", "shardStandby", "playerName");

    private static final ConfigurationScreen.ConfigurationSectionScreen.Filter FILTER =
            (context, key, original) -> SERVER_ONLY.contains(key) ? null : original;

    private ConfigScreen() { }

    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new ConfigurationScreen(c, parent, FILTER));
    }

    public static Screen open(Screen parent) {
        return new ConfigurationScreen(ModList.get().getModContainerById("mcmesh").orElseThrow(), parent, FILTER);
    }
}
