package dev.mcmesh.neoforge.client;

import dev.mcmesh.core.OnboardingWizard;
import dev.mcmesh.neoforge.MeshConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * First-launch graphical onboarding wizard for gateway configuration,
 * invite link redemption, and live connectivity verification.
 */
public final class OnboardingWizardScreen extends Screen {
    private final Screen back;
    private EditBox inputBox;
    private Button verifyBtn;
    private volatile String statusMessage = "Enter gateway URL or paste an invite link to connect.";
    private volatile int statusColor = 0x94A3B8;
    private volatile boolean probing = false;

    public OnboardingWizardScreen(Screen back) {
        super(Component.literal("MCMesh Gateway Setup Wizard"));
        this.back = back;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int startY = height / 2 - 40;

        inputBox = new EditBox(font, centerX - 140, startY, 280, 20, Component.literal("Gateway Address"));
        inputBox.setHint(Component.literal("https://gateway.example.com or invite link"));
        inputBox.setMaxLength(128);
        inputBox.setValue(MeshConfig.API_URL.get() != null ? MeshConfig.API_URL.get() : "");
        addRenderableWidget(inputBox);
        setInitialFocus(inputBox);

        verifyBtn = Button.builder(Component.literal("Verify & Connect"), b -> verifyAndConnect())
                .bounds(centerX - 140, startY + 28, 136, 20)
                .build();
        addRenderableWidget(verifyBtn);

        addRenderableWidget(Button.builder(Component.literal("Community Default"), b -> useDefaultGateway())
                .bounds(centerX + 4, startY + 28, 136, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Skip for Now"), b -> skipWizard())
                .bounds(centerX - 70, startY + 56, 140, 20)
                .build());
    }

    private void useDefaultGateway() {
        inputBox.setValue("https://gateway.mcmesh.net");
        verifyAndConnect();
    }

    private void skipWizard() {
        OnboardingWizard.markFirstLaunchCompleted();
        minecraft.setScreen(back);
    }

    private void verifyAndConnect() {
        String input = inputBox.getValue().strip();
        if (input.isEmpty()) {
            statusMessage = "Please provide a valid gateway URL or invite code.";
            statusColor = 0xF87171;
            return;
        }

        OnboardingWizard.WizardInput parsed = OnboardingWizard.parseInput(input);
        probing = true;
        verifyBtn.active = false;
        statusMessage = "Probing gateway connectivity...";
        statusColor = 0xFBBF24;

        Thread.ofVirtual().name("mesh-wizard-probe").start(() -> {
            String probeUrl = parsed.isInvite() ? ("https://" + parsed.hostOrUrl()) : parsed.hostOrUrl();
            var result = OnboardingWizard.probeGateway(probeUrl);

            minecraft.execute(() -> {
                probing = false;
                verifyBtn.active = true;
                if (result.success()) {
                    statusMessage = String.format("Connected successfully! (%d ms) Saving settings...", result.latencyMs());
                    statusColor = 0x34D399;

                    MeshConfig.API_URL.set(result.gatewayUrl());
                    OnboardingWizard.markFirstLaunchCompleted();

                    // Advance to synced worlds or back screen
                    minecraft.setScreen(new SyncedWorldsScreen(back));
                } else {
                    statusMessage = result.error() != null ? result.error() : "Could not reach gateway.";
                    statusColor = 0xF87171;
                }
            });
        });
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 257 || key == 335) { // Enter
            verifyAndConnect();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        int centerX = width / 2;
        int startY = height / 2 - 80;

        g.drawCenteredString(font, title, centerX, startY, 0x38BDF8);
        g.drawCenteredString(font, "Zero-dependency decentralized Minecraft world mesh network", centerX, startY + 14, 0x94A3B8);

        g.drawCenteredString(font, statusMessage, centerX, startY + 110, statusColor);
    }
}
