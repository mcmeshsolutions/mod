package dev.mcmesh.neoforge.client;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class PromptScreen extends Screen {
    private final Screen back;
    private final String hint;
    private final Consumer<String> done;
    private EditBox box;

    PromptScreen(Screen back, String title, String hint, Consumer<String> done) {
        super(Component.literal(title));
        this.back = back;
        this.hint = hint;
        this.done = done;
    }

    @Override
    protected void init() {
        box = new EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, title);
        box.setHint(Component.literal(hint));
        box.setMaxLength(48);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit()).bounds(width / 2 - 100, height / 2 + 20, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> minecraft.setScreen(back)).bounds(width / 2 + 2, height / 2 + 20, 98, 20).build());
    }

    private void submit() {
        String v = box.getValue().strip();
        minecraft.setScreen(back);
        if (!v.isEmpty()) {
            done.accept(v);
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 257 || key == 335) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        g.drawCenteredString(font, title, width / 2, height / 2 - 34, 0xFFFFFF);
    }
}
