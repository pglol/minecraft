package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The quest journal: active quests (and the story), quests on offer, and finished ones. */
public class JournalScreen extends Screen {
    private static final String[] TABS = {"Active", "Available", "Completed"};
    private static int tab;
    private static String selected = "main";
    private int left, top, w, h, listW, scroll;
    private static final int ROW = 24;

    public JournalScreen() {
        super(Text.literal("Quest Journal"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private List<Net.QuestView> list() {
        List<Net.QuestView> l = new ArrayList<>();
        for (Net.QuestView q : ClientState.quests) {
            boolean main = q.id().equals("main");
            if (tab == 0 && (main || q.state() == 1)) l.add(q);
            if (tab == 1 && !main && q.state() == 0) l.add(q);
            if (tab == 2 && !main && q.state() == 2) l.add(q);
        }
        if (tab == 1) l.sort(Comparator.comparingInt(Net.QuestView::level));
        return l;
    }

    private Net.QuestView current() {
        for (Net.QuestView q : ClientState.quests) if (q.id().equals(selected)) return q;
        List<Net.QuestView> l = list();
        return l.isEmpty() ? null : l.get(0);
    }

    private int visibleRows() {
        return Math.max(1, (h - 12) / ROW);
    }

    @Override
    protected void init() {
        WorldMapScreen.requestIfMissing();
        w = Math.min(460, width - 20);
        left = (width - w) / 2;
        top = 70;
        h = height - top - 12;
        listW = Math.min(200, w / 2);
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            int count = 0;
            int old = tab;
            tab = i;
            count = list().size();
            tab = old;
            addDrawableChild(new AotButton(left + i * 104, top - 22, 100, 20, Ui.heading(TABS[i] + " (" + count + ")"), () -> {
                tab = t;
                scroll = 0;
                List<Net.QuestView> l = list();
                if (!l.isEmpty()) selected = l.get(0).id();
                clearAndInit();
            }).selected(tab == i));
        }
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));

        Net.QuestView q = current();
        if (q == null) return;
        int dx = left + listW + 10, dw = w - listW - 10;
        int by = top + h - 26;
        List<AotButton> buttons = new ArrayList<>();
        if (q.state() == 0) buttons.add(new AotButton(0, by, 0, 18, Ui.heading("Accept"), () -> act(q, "accept")));
        if (q.state() == 1) {
            buttons.add(new AotButton(0, by, 0, 18, Text.literal(q.tracked() ? "◎ Tracked" : "◎ Track"), () -> act(q, "track")).selected(q.tracked()));
            AotButton star = new AotButton(0, by, 0, 18, Text.literal(q.highlightedBy().isEmpty() ? "★ Party" : "★ " + q.highlightedBy()),
                () -> act(q, "highlight")).selected(!q.highlightedBy().isEmpty());
            star.accent = 0xFFD070FF;
            buttons.add(star);
            if (!q.id().equals("main")) buttons.add(new AotButton(0, by, 0, 18, Text.literal("Abandon"), () -> act(q, "abandon")));
        }
        if (q.hasTarget()) buttons.add(new AotButton(0, by, 0, 18, Text.literal("Map"), () -> {
            WorldMapScreen.focus(q.x(), q.z());
            client.setScreen(new WorldMapScreen());
        }));
        if (!buttons.isEmpty()) {
            int bw = (dw - 10 - (buttons.size() - 1) * 4) / buttons.size();
            int bx = dx + 5;
            for (AotButton b : buttons) {
                b.setWidth(bw);
                b.setX(bx);
                bx += bw + 4;
                addDrawableChild(b);
            }
        }
    }

    private void act(Net.QuestView q, String action) {
        ClientPlayNetworking.send(new Net.QuestAction(q.id(), action));
        if (action.equals("accept")) tab = 0;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (mx >= left && mx < left + listW && my >= top + 6 && my < top + h - 6) {
            int i = (int) ((my - top - 6) / ROW) + scroll;
            List<Net.QuestView> l = list();
            if (i >= 0 && i < l.size()) {
                selected = l.get(i).id();
                clearAndInit();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        int max = Math.max(0, list().size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(vAmount)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (AotRpgClient.journalKey().matchesKey(key, scan)) {
            close();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.crest(c, width / 2 - 11, 3, 22, 0.9f);
        Ui.text(c, Ui.title("QUEST JOURNAL"), width / 2f, 27, 1.2f, Ui.GOLD, true);

        Ui.panel(c, left, top, listW, h);
        List<Net.QuestView> l = list();
        int y = top + 6;
        int level = ClientState.profile == null ? 1 : ClientState.profile.level();
        for (int i = scroll; i < l.size() && i < scroll + visibleRows(); i++) {
            Net.QuestView q = l.get(i);
            boolean sel = q.id().equals(selected) || (current() == q);
            if (sel) c.fill(left + 2, y, left + listW - 2, y + ROW - 2, 0x40E0B96A);
            else if (mouseX >= left && mouseX < left + listW && mouseY >= y && mouseY < y + ROW) c.fill(left + 2, y, left + listW - 2, y + ROW - 2, 0x20FFFFFF);
            int col = q.id().equals("main") ? Ui.GOLD : q.level() > level + 5 ? 0xFFE07050 : Ui.CREAM;
            String mark = (q.tracked() ? "◎ " : "") + (q.highlightedBy().isEmpty() ? "" : "★ ");
            c.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(mark + q.title(), listW - 14)), left + 6, y + 2, col);
            String sub = q.category() + (q.id().equals("main") ? "" : "  ·  Lv " + q.level()) + (q.progress().isEmpty() ? "" : "  ·  " + q.progress());
            c.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(sub, listW - 14)), left + 6, y + 12, Ui.MUTED);
            y += ROW;
        }
        if (l.isEmpty()) {
            c.drawCenteredTextWithShadow(textRenderer, Text.literal(tab == 0 ? "No active quests" : "Nothing here yet"),
                left + listW / 2, top + 20, Ui.MUTED);
            if (tab == 1 && ClientState.areas.isEmpty()) Ui.wrapped(c, Text.literal(
                "Side quests come from the world's map data. Ask the server owner to run add-titans.bat on the world, then /aotrpg reload."),
                left + 8, top + 36, listW - 16, Ui.MUTED);
        }

        int dx = left + listW + 10, dw = w - listW - 10;
        Ui.panel(c, dx, top, dw, h);
        Net.QuestView q = current();
        if (q == null) return;
        Ui.text(c, Ui.heading(q.title()), dx + 8, top + 8, 1.15f, Ui.GOLD, false);
        c.drawTextWithShadow(textRenderer, Text.literal(q.category() + (q.id().equals("main") ? "" : "  ·  Recommended level " + q.level())),
            dx + 8, top + 24, q.level() > level + 5 ? 0xFFE07050 : Ui.MUTED);
        Ui.divider(c, dx + 8, top + 38, dw - 16);
        int ty = Ui.wrapped(c, Text.literal(q.text()), dx + 8, top + 46, dw - 16, Ui.CREAM);
        if (!q.progress().isEmpty()) {
            c.drawTextWithShadow(textRenderer, Ui.heading("Progress"), dx + 8, ty + 8, Ui.GOLD);
            c.drawTextWithShadow(textRenderer, Text.literal(q.progress()), dx + 8, ty + 20, Ui.CREAM);
            ty += 28;
        }
        c.drawTextWithShadow(textRenderer, Ui.heading("Reward"), dx + 8, ty + 8, Ui.GOLD);
        c.drawTextWithShadow(textRenderer, Text.literal("+" + q.xp() + " XP" + (q.category().equals("Titan Cave") || q.category().equals("Titan Hunt")
            ? ", gas canisters, emeralds" : q.id().equals("main") ? "" : ", emeralds")), dx + 8, ty + 20, 0xFF8FCB6A);
        if (q.hasTarget() && client.player != null) {
            double ddx = q.x() - client.player.getX(), ddz = q.z() - client.player.getZ();
            c.drawTextWithShadow(textRenderer, Text.literal((int) Math.sqrt(ddx * ddx + ddz * ddz) + " blocks away"), dx + 8, ty + 34, Ui.MUTED);
        }
        if (!q.highlightedBy().isEmpty()) {
            c.drawTextWithShadow(textRenderer, Text.literal("★ Highlighted for your party by " + q.highlightedBy()), dx + 8, ty + 48, 0xFFD070FF);
        }
    }
}
