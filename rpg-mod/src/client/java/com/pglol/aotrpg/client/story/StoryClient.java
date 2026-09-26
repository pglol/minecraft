package com.pglol.aotrpg.client.story;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The story on your screen: chapter cards, film-style subtitles, the shake of a titan's step, the
 * flash of a transformation, the skins of the actors in your scene, and the journal's story pages.
 */
public final class StoryClient {
    private StoryClient() {}

    private static final Map<Integer, String> skins = new HashMap<>();
    public static Net.StoryJournal journal;
    public static Net.DialogueView dialogue;

    private static String cardTitle = "", cardSub = "";
    private static long cardAt = -99999;
    private static final List<Net.StoryLine> lines = new ArrayList<>();
    private static final List<Long> lineAt = new ArrayList<>();
    private static long shakeUntil, flashAt = -99999;
    private static float flashLen = 1;

    public static String skin(int entityId) {
        return skins.get(entityId);
    }

    public static Identifier skinTexture(String skin) {
        return Identifier.of("aot_rpg", "textures/entity/actor/" + (skin == null || skin.isEmpty() ? "civilian_m" : skin) + ".png");
    }

    public static void onActors(Net.Actors a) {
        skins.clear();
        for (Net.ActorInfo i : a.list()) skins.put(i.entity(), i.skin());
    }

    public static void onCard(Net.StoryCard c) {
        cardTitle = c.title();
        cardSub = c.sub();
        cardAt = Util.getMeasuringTimeMs();
    }

    public static void onLine(Net.StoryLine l) {
        lines.add(l);
        lineAt.add(Util.getMeasuringTimeMs());
        while (lines.size() > 3) {
            lines.remove(0);
            lineAt.remove(0);
        }
    }

    public static void onFx(Net.StoryFx f) {
        long now = Util.getMeasuringTimeMs();
        if (f.kind().equals("shake")) shakeUntil = now + (long) (f.seconds() * 1000);
        else {
            flashAt = now;
            flashLen = Math.max(0.2f, f.seconds());
        }
    }

    public static void onDialogue(Net.DialogueView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        dialogue = v.open() ? v : null;
        if (!v.open()) {
            if (mc.currentScreen instanceof DialogueScreen) mc.setScreen(null);
            return;
        }
        if (mc.currentScreen instanceof DialogueScreen s) s.refresh();
        else mc.setScreen(new DialogueScreen());
    }

    /** The ground shaking: a small jitter of the view while it lasts. */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null) skins.clear();
        if (mc.player == null) return;
        long now = Util.getMeasuringTimeMs();
        if (now < shakeUntil) {
            float k = Math.min(1, (shakeUntil - now) / 800f) * 1.6f;
            mc.player.setYaw(mc.player.getYaw() + (mc.world.random.nextFloat() - 0.5f) * k);
            mc.player.setPitch(mc.player.getPitch() + (mc.world.random.nextFloat() - 0.5f) * k);
        }
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        long now = Util.getMeasuringTimeMs();
        // Flash.
        float ft = (now - flashAt) / (flashLen * 1000f);
        if (ft >= 0 && ft < 1) c.fill(0, 0, w, h, (int) (230 * (1 - ft) * (1 - ft)) << 24 | 0xFFF6E6);
        // Chapter card: letterbox bars, a serif title, a quiet line under it.
        long age = now - cardAt;
        long total = 6000;
        if (age < total) {
            float a = age < 800 ? age / 800f : age > total - 1200 ? (total - age) / 1200f : 1;
            int bar = (int) (h * 0.12f * Math.min(1, age / 500f) * (age > total - 700 ? (total - age) / 700f : 1));
            c.fill(0, 0, w, bar, 0xFF000000);
            c.fill(0, h - bar, w, h, 0xFF000000);
            int alpha = Math.max(6, (int) (255 * a));
            Ui.text(c, Ui.title(cardTitle), w / 2f, h * 0.36f, 2.2f, alpha << 24 | 0xEEE4CB, true);
            int rw = 90;
            c.fill(w / 2 - rw, (int) (h * 0.36f) + 26, w / 2 + rw, (int) (h * 0.36f) + 27, alpha * 3 / 4 << 24 | 0x8C7248);
            if (!cardSub.isEmpty()) Ui.text(c, Ui.heading(cardSub), w / 2f, h * 0.36f + 32, 0.9f, alpha << 24 | 0xB09060, true);
        }
        // Subtitles.
        int y = h - 78;
        for (int i = lines.size() - 1; i >= 0; i--) {
            Net.StoryLine l = lines.get(i);
            long la = now - lineAt.get(i);
            long life = 3500 + l.text().length() * 45L;
            if (la > life) continue;
            float a = la > life - 600 ? (life - la) / 600f : 1;
            int alpha = Math.max(6, (int) (255 * a));
            Text t = l.speaker().isEmpty() ? Text.literal(l.text()).styled(s -> s.withItalic(true))
                : Text.literal(l.speaker() + ": ").withColor(0xE0B96A).append(Text.literal(l.text()).withColor(0xFFFFFF));
            int tw = Math.min(w - 40, Ui.font().getWidth(t));
            c.fill(w / 2 - tw / 2 - 6, y - 3, w / 2 + tw / 2 + 6, y + 11, (int) (alpha * 0.55f) << 24);
            Ui.text(c, t, w / 2f, y, 1f, alpha << 24 | (l.speaker().isEmpty() ? 0xD8CFC0 : 0xFFFFFF), true);
            y -= 16;
        }
    }
}
