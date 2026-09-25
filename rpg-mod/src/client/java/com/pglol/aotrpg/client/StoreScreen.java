package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** The server store (will link to the website store). */
public final class StoreScreen extends Screen {
    private final Screen parent;

    public StoreScreen(Screen parent) {
        super(Text.literal("Store"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addDrawableChild(new AotButton(width / 2 - 50, height / 2 + 30, 100, 20, Text.literal("Back"), this::close));
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        int w = 260, h = 110, x = width / 2 - w / 2, y = height / 2 - 60;
        Ui.panel(c, x, y, w, h);
        Ui.crest(c, width / 2 - 20, y + 8, 40, 0.5f);
        Ui.text(c, Ui.title("STORE"), width / 2f, y + 52, 1.4f, Ui.GOLD, true);
        Ui.text(c, Text.literal("Coming soon"), width / 2f, y + 72, 1f, Ui.CREAM, true);
        Ui.text(c, Text.literal("Ranks, cosmetics and more will be on the website store."), width / 2f, y + 88, 0.7f, Ui.MUTED, true);
    }
}
