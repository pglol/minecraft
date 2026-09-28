package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Setting up a duel: ring size, and whether ODM gear, abilities and food are allowed. */
public final class DuelSetupScreen extends Screen {
    private static final String[] RINGS = {"Small", "Medium", "Large", "Huge"};
    private static int ring;
    private static boolean odm = true, abilities = true, food = true;
    private final Net.DuelSetup setup;

    public DuelSetupScreen(Net.DuelSetup setup) {
        super(Text.literal("Duel"));
        this.setup = setup;
    }

    private int pw() { return 300; }
    private int ph() { return 190; }
    private int px() { return width / 2 - pw() / 2; }
    private int py() { return height / 2 - ph() / 2; }

    @Override
    protected void init() {
        int x = px(), y = py(), w = pw();
        addDrawableChild(new AotButton(x + w - 20, y, 20, 20, Text.literal("✕"), this::close));
        int bw = (w - 20 - 3 * 4) / 4;
        for (int i = 0; i < RINGS.length; i++) {
            int k = i;
            addDrawableChild(new AotButton(x + 10 + i * (bw + 4), y + 56, bw, 18, Text.literal(RINGS[i]), () -> {
                ring = k;
                clearAndInit();
            })).selected(ring == i);
        }
        toggle(x + 10, y + 96, "ODM Gear", odm, v -> odm = v);
        toggle(x + 10, y + 118, "Abilities", abilities, v -> abilities = v);
        toggle(x + 10, y + 140, "Food", food, v -> food = v);
        AotButton go = addDrawableChild(new AotButton(x + w / 2 - 70, y + ph() - 26, 140, 20, Ui.heading("Challenge"), () -> {
            ClientPlayNetworking.send(new Net.DuelChallenge(setup.target(), ring, odm, abilities, food));
            close();
        }));
        go.accent = Ui.RED;
    }

    private void toggle(int x, int y, String label, boolean on, java.util.function.Consumer<Boolean> set) {
        int w = pw() - 20, half = (w - 100 - 4) / 2;
        addDrawableChild(new AotButton(x + 100, y, half, 18, Text.literal("On"), () -> {
            set.accept(true);
            clearAndInit();
        })).selected(on);
        AotButton off = addDrawableChild(new AotButton(x + 104 + half, y, half, 18, Text.literal("Off"), () -> {
            set.accept(false);
            clearAndInit();
        }));
        off.accent = Ui.RED;
        off.selected(!on);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        int x = px(), y = py(), w = pw();
        Ui.panel(c, x, y, w, ph());
        Ui.text(c, Ui.title("DUEL"), x + 12, y + 7, 1.3f, Ui.GOLD, false);
        Ui.text(c, Text.literal("vs " + setup.name()), x + 12, y + 26, 0.9f, Ui.CREAM, false);
        Ui.text(c, Ui.heading("Ring"), x + 10, y + 44, 0.8f, Ui.GOLD, false);
        Ui.text(c, Ui.heading("ODM Gear"), x + 10, y + 101, 0.8f, Ui.GOLD, false);
        Ui.text(c, Ui.heading("Abilities"), x + 10, y + 123, 0.8f, Ui.GOLD, false);
        Ui.text(c, Ui.heading("Food"), x + 10, y + 145, 0.8f, Ui.GOLD, false);
    }

    @Override
    public boolean shouldPause() { return false; }
}
