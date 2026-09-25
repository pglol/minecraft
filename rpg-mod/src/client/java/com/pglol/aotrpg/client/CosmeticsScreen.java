package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Cosmetics: looks only. Locked ones show but cannot be picked; operators hand them out. */
public final class CosmeticsScreen extends Screen {
    record Entry(String id, String title, String desc, int color) { }

    static final Entry[] TRAILS = {
        new Entry("trail_tracer", "Tracer", "A clean white tracer. Standard issue.", 0xFFEDE3C8),
        new Entry("trail_ember", "Ember", "Sparks and smoke, like a flintlock.", 0xFFE0782A),
        new Entry("trail_frost", "Frost", "An icy streak with snowflakes.", 0xFF8FD8FF),
        new Entry("trail_lightning", "Thunder", "A crackling electric bolt.", 0xFFB9A8FF),
        new Entry("trail_rainbow", "Rainbow", "Every colour at once.", 0xFFFF6FD8),
        new Entry("trail_confetti", "Confetti", "Party popper shots.", 0xFFF2C14E),
        new Entry("trail_hearts", "Hearts", "Shoot with love.", 0xFFFF5A7A),
        new Entry("trail_void", "Void", "A dark smoky rift.", 0xFF7A3AB8),
    };

    private int left, top, w, h;

    public CosmeticsScreen() {
        super(Text.literal("Cosmetics"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    @Override
    protected void init() {
        w = Math.min(420, width - 20);
        left = (width - w) / 2;
        top = 60;
        h = height - top - 20;
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        int y = top + 30;
        for (Entry e : TRAILS) {
            boolean owned = ClientState.cosmetics.contains(e.id());
            boolean sel = e.id().equals(ClientState.trail);
            AotButton b = new AotButton(left + w - 100, y + 3, 88, 18,
                Ui.heading(sel ? "Equipped" : owned ? "Equip" : "Locked"), () -> {
                    if (owned) ClientPlayNetworking.send(new Net.SelectCosmetic(e.id()));
                });
            b.selected(sel);
            b.active = owned && !sel;
            addDrawableChild(b);
            y += 26;
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.crest(c, width / 2 - 11, 3, 22, 0.9f);
        Ui.text(c, Ui.title("COSMETICS"), width / 2f, 27, 1.2f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Ui.text(c, Ui.heading("Bullet Trails"), left + 10, top + 8, 1f, Ui.GOLD, false);
        Ui.text(c, Text.literal(ClientState.cosmeticsAll ? "All unlocked" : "Looks only, never power"),
            left + w - 10 - Ui.font().getWidth("Looks only, never power") * 0.75f, top + 10, 0.75f, Ui.MUTED, false);
        int y = top + 30;
        for (Entry e : TRAILS) {
            boolean owned = ClientState.cosmetics.contains(e.id());
            c.fill(left + 8, y, left + w - 8, y + 24, 0x40000000);
            c.fill(left + 8, y, left + 11, y + 24, owned ? e.color() : 0xFF55524A);
            // A little preview streak
            for (int i = 0; i < 24; i++) {
                int col = e.id().equals("trail_rainbow") ? 0xFF000000 | net.minecraft.util.math.MathHelper.hsvToRgb(i / 24f, 0.8f, 1f) : e.color();
                int a = owned ? 60 + i * 8 : 40;
                c.fill(left + 16 + i * 2, y + 11, left + 18 + i * 2, y + 13, (a << 24) | (col & 0xFFFFFF));
            }
            Ui.text(c, Ui.heading(e.title()), left + 72, y + 3, 1f, owned ? Ui.CREAM : Ui.DIM, false);
            Ui.text(c, Text.literal(e.desc()), left + 72, y + 14, 0.7f, Ui.MUTED, false);
            y += 26;
        }
    }
}
