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
    private volatile String inviteUrl;
    private volatile boolean failed;
    private Button doneBtn;
    private Button copyInviteBtn;
    private Button hostNowBtn;

    public ConvertScreen(Screen back, Path save, String name) {
        super(Component.literal("Convert to Shared Mesh World"));
        this.back = back;
        this.save = save;
        this.name = name;
        Thread.ofPlatform().name("mesh-convert").daemon().start(this::run);
    }

    private void run() {
        try {
            WorldConverter.Result r = WorldConverter.convert(save, name, s -> status = s);
            outcome = "World '" + r.worldId() + "' uploaded and ready!";
            inviteUrl = r.inviteUrl();
        } catch (Exception e) {
            LOG.error("conversion failed", e);
            failed = true;
            outcome = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        copyInviteBtn = addRenderableWidget(Button.builder(Component.literal("Copy Invite Link"), b -> {
            if (inviteUrl != null && !inviteUrl.isEmpty() && minecraft != null) {
                minecraft.keyboardHandler.setClipboard(inviteUrl);
                b.setMessage(Component.literal("Copied to Clipboard!"));
            }
        }).bounds(cx - 155, cy + 25, 150, 20).build());

        hostNowBtn = addRenderableWidget(Button.builder(Component.literal("Host World Now"), b -> {
            if (minecraft != null) {
                minecraft.setScreen(new SyncedWorldsScreen(back));
            }
        }).bounds(cx + 5, cy + 25, 150, 20).build());

        doneBtn = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
            if (minecraft != null) {
                minecraft.setScreen(back);
            }
        }).bounds(cx - 75, cy + 55, 150, 20).build());

        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean finished = outcome != null;
        if (doneBtn != null) doneBtn.active = finished;
        if (copyInviteBtn != null) {
            copyInviteBtn.visible = finished && !failed && inviteUrl != null && !inviteUrl.isEmpty();
            copyInviteBtn.active = finished && !failed;
        }
        if (hostNowBtn != null) {
            hostNowBtn.visible = finished && !failed;
            hostNowBtn.active = finished && !failed;
        }
    }

    @Override
    public void tick() {
        updateButtonStates();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return outcome != null;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        g.drawCenteredString(font, title, width / 2, height / 2 - 50, 0xFFFFFF);
        String o = outcome;
        if (o == null) {
            g.drawCenteredString(font, status + "...", width / 2, height / 2 - 15, 0xAAAAAA);
        } else {
            g.drawCenteredString(font, failed ? "Conversion Failed" : "World Converted Successfully!", width / 2, height / 2 - 25, failed ? 0xFF5555 : 0x55FF55);
            g.drawCenteredString(font, o, width / 2, height / 2 - 10, 0xFFFFFF);
            if (inviteUrl != null && !inviteUrl.isEmpty()) {
                g.drawCenteredString(font, "\u00a7bShareable Invite: \u00a7f" + inviteUrl, width / 2, height / 2 + 5, 0x38BDF8);
            }
        }
    }
}
