package dev.mcmesh.neoforge.client;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** The settings screen: NeoForge builds it from the config spec, the names and hints come from the lang file. Reached from the Mods list and the title screen. */
public final class ConfigScreen {
    private ConfigScreen() { }

    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    public static Screen open(Screen parent) {
        return new ConfigurationScreen(ModList.get().getModContainerById("mcmesh").orElseThrow(), parent);
    }
}
