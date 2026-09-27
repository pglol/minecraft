package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.Locale;

/**
 * A town house's door. Residents go in (or change their mind); everyone sees who lives here and
 * can knock on any resident who's in (party members just walk in); anyone can look at the deed.
 */
public final class DoorScreen extends Screen {
    private final Net.DoorView v;
    private int left, top, w, h;
    private final long opened = Util.getMeasuringTimeMs();

    public DoorScreen(Net.DoorView v) {
        super(Text.literal("Door"));
        this.v = v;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void act(String a, String arg) {
        ClientPlayNetworking.send(new Net.HomeAction(a, v.home(), arg));
    }

    @Override
    protected void init() {
        w = Math.min(300, width - 20);
        int rows = Math.min(6, v.tenants().size());
        h = 118 + Math.max(1, rows) * 22;
        left = (width - w) / 2;
        top = Math.max(16, (height - h) / 2);
        int by = top + h - 30;
        if (v.owned()) {
            addDrawableChild(new AotButton(left + 10, by, 120, 22, Ui.heading("Go inside"), () -> {
                act("enter", "");
                close();
            })).selected(true);
            addDrawableChild(new AotButton(left + 136, by, 70, 22, Text.literal("Stay out"), this::close));
            addDrawableChild(new AotButton(left + w - 88, by, 78, 22, Text.literal("Upgrades"), () -> act("deed", "")));
        } else {
            addDrawableChild(new AotButton(left + 10, by, 90, 22, Text.literal("Leave"), this::close));
            AotButton deed = addDrawableChild(new AotButton(left + w - 160, by, 150, 22,
                Ui.heading(v.homes() >= v.maxHomes() ? "Deed" : "Buy · " + String.format(Locale.ROOT, "%,d", v.price()) + " M"), () -> act("deed", "")));
            deed.selected(v.homes() < v.maxHomes());
        }
        int y = top + 78;
        for (int i = 0; i < v.tenants().size() && i < 6; i++) {
            Net.DoorTenant t = v.tenants().get(i);
            if (t.you()) {
                y += 22;
                continue;
            }
            String label = t.party() ? "Walk in" : t.online() ? "Knock" : "Away";
            AotButton b = addDrawableChild(new AotButton(left + w - 78, y + 1, 68, 18, Text.literal(label), () -> {
                act("knock", t.id().toString());
                close();
            }));
            b.active = t.online();
            y += 22;
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0x90000000, 0xC0000000);
        float k = Math.min(1, (Util.getMeasuringTimeMs() - opened) / 200f);
        int slide = (int) ((1 - k) * 12);
        Ui.panel(c, left, top + slide, w, h);
        // A little door, lit from within when it's yours.
        int dx = left + 14, dy = top + 12 + slide;
        c.fill(dx, dy, dx + 22, dy + 34, 0xFF5A3A1A);
        c.fill(dx + 3, dy + 3, dx + 19, dy + 16, 0xFF3A2410);
        c.fill(dx + 3, dy + 19, dx + 19, dy + 31, 0xFF3A2410);
        c.fill(dx + 16, dy + 17, dx + 18, dy + 19, Ui.GOLD);
        if (v.owned()) c.fill(dx - 2, dy + 34, dx + 24, dy + 36, 0xC0F2C14E);
        Ui.text(c, Ui.heading(v.town()), left + 44, top + 14 + slide, 1.1f, Ui.CREAM, false);
        Ui.text(c, Text.literal(v.size()), left + 44, top + 28 + slide, 0.75f, Ui.MUTED, false);
        String status = v.owned() ? "Your home" : v.tenants().isEmpty() ? "For sale · every buyer gets their own private copy"
            : "Lived in · for sale too, every buyer gets their own copy";
        Ui.text(c, Text.literal(status), left + 44, top + 40 + slide, 0.7f, v.owned() ? Ui.GOLD : Ui.MUTED, false);
        Ui.divider(c, left + 10, top + 56 + slide, w - 20);
        Ui.text(c, Ui.heading("Residents"), left + 12, top + 63 + slide, 0.85f, Ui.GOLD, false);
        int y = top + 78 + slide;
        if (v.tenants().isEmpty()) Ui.text(c, Text.literal("Nobody lives here yet."), left + 14, y + 5, 0.8f, Ui.MUTED, false);
        for (int i = 0; i < v.tenants().size() && i < 6; i++) {
            Net.DoorTenant t = v.tenants().get(i);
            c.fill(left + 10, y, left + w - 10, y + 20, t.you() ? 0x30E0B96A : 0x28000000);
            c.fill(left + 16, y + 7, left + 22, y + 13, t.online() ? 0xFF5BD35B : 0xFF55524A);
            Ui.text(c, Text.literal(t.name() + (t.you() ? "  (you)" : "")), left + 28, y + 6, 0.85f, t.online() ? Ui.CREAM : Ui.MUTED, false);
            if (t.party()) Ui.text(c, Text.literal("party"), left + w - 118, y + 7, 0.6f, 0xFF5BD35B, false);
            y += 22;
        }
    }
}
