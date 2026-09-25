package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * Horses, in the mod's own look: the Stable Master (horses of the region for sale, the first-ride
 * quest), your stables at home, and a single horse's card when you sneak-click it or open its
 * inventory while riding.
 */
public final class StableScreen extends Screen {
    private static final int ROW = 46;
    private static final int[] COAT = {0xFFE8E0D0, 0xFFC9A36A, 0xFF8A5A2E, 0xFF6A3A1E, 0xFF2A2A2A, 0xFF9A9A9A, 0xFF4A2E1A};
    private int left, top, w, h, tab, scroll;
    private TextFieldWidget nameField;
    private String typed = "";

    public StableScreen() {
        super(Text.literal("Stable"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        if (nameField != null) typed = nameField.getText();
        clearAndInit();
    }

    private static void act(String a, String horse, String arg) {
        ClientPlayNetworking.send(new Net.StableAction(a, horse, arg));
    }

    @Override
    public void removed() {
        act("close", "", "");
    }

    private Net.StableView v() {
        return ClientState.stable;
    }

    private boolean master() {
        return v() != null && v().mode().equals("master");
    }

    private boolean single() {
        return v() != null && v().mode().equals("horse");
    }

    private int visible() {
        return Math.max(1, (h - 96) / ROW);
    }

    @Override
    protected void init() {
        Net.StableView v = v();
        w = Math.min(560, width - 20);
        h = Math.min(single() ? 220 : 340, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 6);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        if (v == null) return;
        if (master()) {
            String[] tabs = {"Horses for sale", "Your horses"};
            for (int i = 0; i < tabs.length; i++) {
                int t = i;
                addDrawableChild(new AotButton(left + i * 144, top - 22, 140, 20, Ui.heading(tabs[i]), () -> {
                    tab = t;
                    scroll = 0;
                    refresh();
                })).selected(tab == i);
            }
        } else {
            tab = 1;
        }
        // Name box: for a new horse, the first-ride horse, or renaming.
        boolean naming = master() || v.atStable();
        if (naming && !single()) {
            nameField = new TextFieldWidget(textRenderer, left + 120, top + 10, 150, 14, Text.literal("Name"));
            nameField.setMaxLength(24);
            nameField.setDrawsBackground(false);
            nameField.setText(typed);
            nameField.setEditableColor(0xFFEDE3C8);
            addDrawableChild(nameField);
        } else {
            nameField = null;
        }
        if (single()) {
            initSingle(v);
            return;
        }
        int y = top + 32;
        if (master() && v.quest() == 0 && v.horses().isEmpty() && tab == 0) {
            addDrawableChild(new AotButton(left + w - 160, y + 10, 150, 20, Ui.heading("Accept: The First Ride"),
                () -> act("quest", "", nameField == null ? "" : nameField.getText()))).selected(true);
            y += 44;
        }
        if (tab == 0) {
            List<Net.BreedEntry> list = v.breeds();
            scroll = Math.max(0, Math.min(scroll, list.size() - visible()));
            for (int i = scroll; i < list.size() && i < scroll + visible(); i++) {
                Net.BreedEntry b = list.get(i);
                AotButton buy = addDrawableChild(new AotButton(left + w - 110, y + 13, 100, 20,
                    Ui.heading(String.format(Locale.ROOT, "Buy · %,d", b.price())),
                    () -> act("buy", nameField == null ? "" : nameField.getText(), b.id())));
                buy.active = ClientState.marks >= b.price() && v.horses().size() < v.capacity() && v.quest() != 1;
                y += ROW;
            }
        } else {
            List<Net.HorseEntry> list = v.horses();
            scroll = Math.max(0, Math.min(scroll, list.size() - visible()));
            for (int i = scroll; i < list.size() && i < scroll + visible(); i++) {
                Net.HorseEntry e = list.get(i);
                int bx = left + w - 10;
                if (v.atStable()) {
                    bx -= 70;
                    AotButton ride = addDrawableChild(new AotButton(bx, y + 4, 66, 18, Text.literal(e.active() ? "Riding out" : "Ride out"),
                        () -> act("select", e.id(), "")));
                    ride.selected(e.active());
                    ride.active = !e.active() && !e.lent();
                    addDrawableChild(new AotButton(bx, y + 24, 66, 18, Text.literal("Rename"), () -> {
                        if (nameField != null && !nameField.getText().isBlank()) act("rename", e.id(), nameField.getText());
                    })).active = nameField != null;
                    bx -= 70;
                    addDrawableChild(new AotButton(bx, y + 24, 66, 18, Text.literal("Sell"), () -> act("release", e.id(), "")))
                        .active = !e.lent() && list.size() > 1;
                }
                if (!e.saddle()) {
                    bx -= 90;
                    addDrawableChild(new AotButton(bx + (v.atStable() ? 70 : 0), y + 4, 86, 18, Text.literal("Saddle · 350"),
                        () -> act("saddle", e.id(), ""))).active = ClientState.marks >= 350;
                }
                y += ROW;
            }
        }
    }

    private void initSingle(Net.StableView v) {
        Net.HorseEntry e = null;
        for (Net.HorseEntry x : v.horses()) if (x.out()) e = x;
        if (e == null) for (Net.HorseEntry x : v.horses()) if (x.active()) e = x;
        if (e == null) return;
        Net.HorseEntry horse = e;
        int y = top + h - 30;
        if (!horse.saddle()) {
            addDrawableChild(new AotButton(left + 10, y, 120, 20, Ui.heading("Saddle · 350 M"), () -> act("saddle", horse.id(), "")))
                .active = ClientState.marks >= 350;
        }
        addDrawableChild(new AotButton(left + w - 130, y, 120, 20, Ui.heading("Send away"), () -> {
            act("dismiss", horse.id(), "");
            close();
        }));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        scroll -= (int) Math.signum(vAmount);
        refresh();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Net.StableView v = v();
        String title = v == null ? "STABLE" : master() ? "STABLE MASTER" : single() ? "YOUR HORSE" : "YOUR STABLES";
        Ui.text(c, Ui.title(title), width / 2f, top - 44, 1.4f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        if (v == null) return;
        if (single()) {
            renderSingle(c, v);
            return;
        }
        if (nameField != null) {
            Ui.text(c, Text.literal(master() ? "Name your horse (optional)" : "New name"), left + 10, top + 12, 0.7f, Ui.MUTED, false);
            c.fill(left + 116, top + 7, left + 274, top + 21, 0xC0000000);
            c.drawBorder(left + 116, top + 7, 158, 14, nameField.isFocused() ? Ui.GOLD : 0xFF5A4A30);
        }
        String cap = v.horses().size() + " / " + v.capacity() + " horses";
        Ui.text(c, Text.literal(cap), left + w - 10 - Ui.font().getWidth(cap) * 0.75f, top + 11, 0.75f, v.horses().size() >= v.capacity() ? Ui.RED : Ui.MUTED, false);
        int y = top + 32;
        if (master() && v.quest() == 0 && v.horses().isEmpty() && tab == 0) {
            c.fill(left + 6, y, left + w - 6, y + 40, 0x40E0B96A);
            Ui.text(c, Ui.heading("The First Ride"), left + 12, y + 5, 0.95f, Ui.GOLD, false);
            Ui.wrapped(c, Text.literal("The Stable Master lends you a horse and saddle. Ride it " + (int) v.questGoal()
                + " blocks and it's yours to keep. Press N (or your whistle) to call it."), left + 12, y + 17, w - 190, Ui.CREAM);
            y += 44;
        } else if (v.quest() == 1) {
            Ui.text(c, Text.literal("The First Ride: " + (int) v.questDist() + " / " + (int) v.questGoal() + " blocks"), left + 10, top + h - 14, 0.7f,
                Ui.GOLD, false);
        }
        if (tab == 0) {
            List<Net.BreedEntry> list = v.breeds();
            if (list.isEmpty()) Ui.text(c, Text.literal("No horses for sale here."), left + w / 2f, y + 20, 0.8f, Ui.DIM, true);
            for (int i = scroll; i < list.size() && i < scroll + visible(); i++) {
                Net.BreedEntry b = list.get(i);
                c.fill(left + 6, y, left + w - 6, y + ROW - 4, 0x30000000);
                Ui.text(c, Ui.heading(b.title()), left + 12, y + 4, 0.95f, Ui.CREAM, false);
                Ui.text(c, Text.literal(b.blurb()), left + 12, y + 15, 0.62f, Ui.MUTED, false);
                String stats = String.format(Locale.ROOT, "Speed %.1f-%.1f b/s   Jump %.2f-%.2f   Health %d-%d   Max level %d",
                    b.speedMin(), b.speedMax(), b.jumpMin(), b.jumpMax(), b.hpMin(), b.hpMax(), b.cap());
                Ui.text(c, Text.literal(stats), left + 12, y + 27, 0.62f, 0xFF8FCB6A, false);
                speedBar(c, left + 12, y + 37, 200, b.speedMin(), b.speedMax());
                y += ROW;
            }
            if (v.horses().size() >= v.capacity()) {
                Ui.text(c, Text.literal("Your stables are full: build a Stable at your home or property for more."), left + 10, top + h - 26, 0.65f,
                    Ui.RED, false);
            }
        } else {
            List<Net.HorseEntry> list = v.horses();
            if (list.isEmpty()) Ui.text(c, Text.literal("No horses yet."), left + w / 2f, y + 20, 0.8f, Ui.DIM, true);
            for (int i = scroll; i < list.size() && i < scroll + visible(); i++) {
                horseRow(c, list.get(i), y);
                y += ROW;
            }
            if (!v.atStable()) Ui.text(c, Text.literal("Swap your ride-out horse or rename at a Stable Master or your home stables."),
                left + 10, top + h - 14, 0.62f, Ui.MUTED, false);
        }
    }

    private void horseRow(DrawContext c, Net.HorseEntry e, int y) {
        c.fill(left + 6, y, left + w - 6, y + ROW - 4, e.active() ? 0x40E0B96A : 0x30000000);
        c.fill(left + 10, y + 6, left + 24, y + 20, COAT[Math.floorMod(e.color(), COAT.length)]);
        c.drawBorder(left + 10, y + 6, 14, 14, 0xFF101010);
        Ui.text(c, Ui.heading(e.name()), left + 30, y + 4, 0.95f, Ui.CREAM, false);
        String tags = e.breed() + (e.lent() ? " · lent" : "") + (e.out() ? " · out with you" : "") + (e.resting() ? " · recovering" : "")
            + (e.saddle() ? "" : " · no saddle");
        Ui.text(c, Text.literal(tags), left + 30, y + 15, 0.62f, e.resting() ? Ui.RED : Ui.MUTED, false);
        Ui.text(c, Text.literal("Lv " + e.level() + " / " + e.cap()), left + 30, y + 26, 0.7f, Ui.GOLD, false);
        Ui.bar(c, left + 76, y + 27, 90, 3, e.level() >= e.cap() ? 1 : e.xp() / (float) Math.max(1, e.xpNext()), Ui.XP);
        String stats = String.format(Locale.ROOT, "%.1f b/s  ·  jump %.2f  ·  %d hp", e.speed(), e.jump(), Math.round(e.health()));
        Ui.text(c, Text.literal(stats), left + 172, y + 26, 0.62f, 0xFF8FCB6A, false);
        speedBar(c, left + 30, y + 36, 136, e.speed(), e.speed());
    }

    /** Speed against the fastest horses (about 16 blocks/s). */
    private static void speedBar(DrawContext c, int x, int y, int w, float lo, float hi) {
        float max = 16.5f;
        c.fill(x, y, x + w, y + 3, 0xFF1A1E1A);
        int a = (int) (w * Math.min(1, lo / max)), b = (int) (w * Math.min(1, hi / max));
        c.fill(x, y, x + a, y + 3, 0xFF5E9A4A);
        if (b > a) c.fill(x + a, y, x + b, y + 3, 0x805E9A4A);
    }

    private void renderSingle(DrawContext c, Net.StableView v) {
        Net.HorseEntry e = null;
        for (Net.HorseEntry x : v.horses()) if (x.out()) e = x;
        if (e == null) for (Net.HorseEntry x : v.horses()) if (x.active()) e = x;
        if (e == null) return;
        int y = top + 12;
        c.fill(left + 12, y, left + 44, y + 32, COAT[Math.floorMod(e.color(), COAT.length)]);
        c.drawBorder(left + 12, y, 32, 32, Ui.GOLD);
        Ui.text(c, Ui.heading(e.name()), left + 54, y + 2, 1.2f, Ui.CREAM, false);
        Ui.text(c, Text.literal(e.breed() + (e.lent() ? " · lent by the Stable Master" : "")), left + 54, y + 18, 0.7f, Ui.MUTED, false);
        y += 44;
        Ui.text(c, Text.literal("Level " + e.level() + " of " + e.cap()), left + 12, y, 0.85f, Ui.GOLD, false);
        Ui.bar(c, left + 12, y + 11, w - 24, 5, e.level() >= e.cap() ? 1 : e.xp() / (float) Math.max(1, e.xpNext()), Ui.XP);
        Ui.text(c, Text.literal(e.level() >= e.cap() ? "At its breed's limit" : e.xp() + " / " + e.xpNext() + " XP · earned by riding"),
            left + 12, y + 19, 0.6f, Ui.MUTED, false);
        y += 34;
        String[] labels = {"Speed", "Jump", "Health"};
        String[] values = {String.format(Locale.ROOT, "%.1f blocks/s", e.speed()), String.format(Locale.ROOT, "%.2f", e.jump()),
            String.valueOf(Math.round(e.health()))};
        float[] frac = {e.speed() / 16.5f, e.jump() / 1.2f, e.health() / 45f};
        for (int i = 0; i < 3; i++) {
            Ui.text(c, Text.literal(labels[i]), left + 12, y, 0.75f, Ui.CREAM, false);
            Ui.bar(c, left + 70, y + 1, w - 200, 5, Math.min(1, frac[i]), 0xFF5E9A4A);
            Ui.text(c, Text.literal(values[i]), left + w - 120, y, 0.75f, Ui.GOLD, false);
            y += 14;
        }
        String tack = (e.saddle() ? "Saddled" : "No saddle") + " · " + (e.armor().isEmpty() ? "no armor" : e.armor().replace("minecraft:", "").replace('_', ' '));
        Ui.text(c, Text.literal(tack), left + 12, y + 4, 0.7f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Press N or use your whistle to call or send away. Rename and swap at a Stable Master or at home."),
            left + 12, top + h - 44, 0.6f, Ui.DIM, false);
    }
}
