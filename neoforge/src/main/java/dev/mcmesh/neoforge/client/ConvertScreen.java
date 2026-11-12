package dev.mcmesh.neoforge.client;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public final class ConvertScreen extends Screen {
    private static final Logger LOG = LogUtils.getLogger();

    private final Screen back;
    private final Path save;
    private final String name;
    private volatile String status = "Starting";
    private volatile String outcome;
    private volatile boolean failed;
    private Button done;

    public ConvertScreen(Screen back, Path save, String name) {
        super(Component.literal("Convert to shared world"));
        this.back = back;
        this.save = save;
        this.name = name;
        Thread.ofPlatform().name("mesh-convert").daemon().start(this::run);
    }

    private void run() {
        try {
            WorldConverter.Result r = WorldConverter.convert(save, name, s -> status = s);
            outcome = "World id " + r.worldId() + ". Host it from Synced worlds.";
        } catch (Exception e) {
            LOG.error("conversion failed", e);
            failed = true;
            outcome = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    @Override
    protected void init() {
        done = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> minecraft.setScreen(back))
                .bounds(width / 2 - 75, height / 2 + 40, 150, 20).build());
        done.active = outcome != null;
    }

    @Override
    public void tick() {
        if (done != null) {
            done.active = outcome != null;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return outcome != null;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        g.drawCenteredString(font, title, width / 2, height / 2 - 40, 0xFFFFFF);
        String o = outcome;
        if (o == null) {
            g.drawCenteredString(font, status + "...", width / 2, height / 2 - 10, 0xAAAAAA);
        } else {
            g.drawCenteredString(font, failed ? "Conversion failed" : "Done", width / 2, height / 2 - 14, failed ? 0xFF5555 : 0x55FF55);
            g.drawCenteredString(font, o, width / 2, height / 2 + 2, 0xFFFFFF);
        }
    }
}
