package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.List;

/** A conversation: who you're talking to along the bottom of the screen, their words, and your replies. */
public final class TalkScreen extends Screen {
    private Net.Talk talk;
    private long shownAt;

    public TalkScreen(Net.Talk t) {
        super(Text.literal("Talk"));
        this.talk = t;
        this.shownAt = Util.getMeasuringTimeMs();
    }

    public static void on(Net.Talk t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof TalkScreen s) {
            s.talk = t;
            s.shownAt = Util.getMeasuringTimeMs();
            s.clearAndInit();
        } else {
            mc.setScreen(new TalkScreen(t));
        }
    }

    private int boxW() { return Math.min(width - 40, 520); }
    private int boxX() { return width / 2 - boxW() / 2; }
    private int boxY() { return height - 150; }

    @Override
    protected void init() {
        List<String> opts = talk.options().isEmpty() ? List.of("(Leave)") : talk.options();
        int bw = 200, x = boxX() + boxW() - bw - 10;
        int y = boxY() - opts.size() * 20 - 6;
        for (String o : opts) {
            AotButton b = addDrawableChild(new AotButton(x, y, bw, 18, Text.literal(o), () -> {
                if (talk.options().isEmpty() || o.equals("Goodbye.") || o.startsWith("Let them")) {
                    if (!talk.options().isEmpty()) ClientPlayNetworking.send(new Net.TalkChoice(talk.entity(), o));
                    if (talk.options().isEmpty() || o.startsWith("Let them")) close();
                } else {
                    ClientPlayNetworking.send(new Net.TalkChoice(talk.entity(), o));
                }
            }));
            if (o.equals("Goodbye.") || o.equals("(Leave)")) b.accent = Ui.RED;
            y += 20;
        }
    }

    @Override
    public void tick() {
        // Walk away (or they do) and the conversation ends.
        MinecraftClient mc = MinecraftClient.getInstance();
        var e = mc.world == null ? null : mc.world.getEntityById(talk.entity());
        if (e == null || mc.player == null || e.squaredDistanceTo(mc.player) > 9 * 9) close();
        if (talk.options().isEmpty() && Util.getMeasuringTimeMs() - shownAt > 3500) close();
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        // No dimming: the world stays in view, the talk sits at the bottom.
        int x = boxX(), y = boxY(), w = boxW(), h = 96;
        c.fillGradient(0, height - 190, width, height, 0x00000000, 0xA0000000);
        Ui.panel(c, x, y, w, h);
        Ui.text(c, Ui.heading(talk.name()), x + 14, y + 10, 1.1f, Ui.GOLD, false);
        Ui.text(c, Text.literal(talk.sub()), x + 14, y + 24, 0.75f, Ui.CREAM, false);
        Ui.divider(c, x + 12, y + 34, w - 24);
        // Their words, typed out as they say them.
        int shown = (int) Math.min(talk.line().length(), (Util.getMeasuringTimeMs() - shownAt) / 18);
        String line = talk.line().substring(0, shown);
        Ui.wrapped(c, Text.literal(line), x + 14, y + 42, w - 28, 0xFFF2EBD8);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Skip the typing with space.
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            shownAt = 0;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
