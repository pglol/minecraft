package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Task boards (daily, weekly, monthly, season) and achievements, whose titles you can wear. */
public final class TasksScreen extends Screen {
    private static final String[] TABS = {"Daily", "Weekly", "Monthly", "Season", "Achievements"};
    private static final int ROW = 34;
    private int left, top, w, h, tab, scroll;

    public TasksScreen() {
        super(Text.literal("Tasks"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private List<Net.TaskEntry> tasks() {
        List<Net.TaskEntry> out = new ArrayList<>();
        if (ClientState.tasks != null) for (Net.TaskEntry t : ClientState.tasks.tasks()) if (t.period() == tab) out.add(t);
        return out;
    }

    private int rows() {
        return tab == 4 ? (ClientState.tasks == null ? 0 : ClientState.tasks.achievements().size()) : tasks().size();
    }

    private int visible() {
        return Math.max(1, (h - 64) / ROW);
    }

    @Override
    protected void init() {
        w = Math.min(540, width - 20);
        h = Math.min(330, height - 70);
        left = (width - w) / 2;
        top = Math.max(50, (height - h) / 2);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        int tw = Math.min(100, (w - 30) / TABS.length);
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            addDrawableChild(new AotButton(left + i * (tw + 2), top - 22, tw, 20, Ui.heading(TABS[i]), () -> {
                tab = t;
                scroll = 0;
                clearAndInit();
            })).selected(tab == i);
        }
        Net.TasksView v = ClientState.tasks;
        if (v == null) return;
        scroll = Math.max(0, Math.min(scroll, rows() - visible()));
        int y = top + 30;
        if (tab < 4) {
            boolean any = false;
            for (Net.TaskEntry t : v.tasks()) if (!t.claimed() && t.progress() >= t.goal()) any = true;
            addDrawableChild(new AotButton(left + w - 120, top + h - 26, 110, 20, Ui.heading("Claim all"),
                () -> ClientPlayNetworking.send(new Net.TaskAction("claimall", "")))).active = any;
            List<Net.TaskEntry> list = tasks();
            for (int i = scroll; i < list.size() && i < scroll + visible(); i++) {
                Net.TaskEntry t = list.get(i);
                boolean done = t.progress() >= t.goal();
                AotButton b = addDrawableChild(new AotButton(left + w - 96, y + 7, 84, 18,
                    Ui.heading(t.claimed() ? "Claimed" : done ? "Claim" : t.progress() + " / " + t.goal()),
                    () -> ClientPlayNetworking.send(new Net.TaskAction("claim", t.id()))));
                b.active = done && !t.claimed();
                b.selected(done && !t.claimed());
                y += ROW;
            }
        } else {
            addDrawableChild(new AotButton(left + w - 150, top + h - 26, 140, 20, Text.literal("Hide my title"),
                () -> ClientPlayNetworking.send(new Net.TaskAction("title", "")))).active = !v.title().isEmpty();
            List<Net.AchievementEntry> list = v.achievements();
            for (int i = scroll; i < list.size() && i < scroll + visible(); i++) {
                Net.AchievementEntry a = list.get(i);
                if (a.earned()) {
                    boolean wearing = a.id().equals(v.title());
                    addDrawableChild(new AotButton(left + w - 96, y + 7, 84, 18, Ui.heading(wearing ? "Wearing" : "Wear title"),
                        () -> ClientPlayNetworking.send(new Net.TaskAction("title", a.id())))).selected(wearing);
                }
                y += ROW;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        scroll -= (int) Math.signum(vAmount);
        clearAndInit();
        return true;
    }

    private static String left(long s) {
        if (s < 0) return "until the season ends";
        long d = s / 86400, hr = s / 3600 % 24, m = s / 60 % 60;
        return "resets in " + (d > 0 ? d + "d " + hr + "h" : hr > 0 ? hr + "h " + m + "m" : m + "m");
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("TASKS"), width / 2f, top - 44, 1.4f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.TasksView v = ClientState.tasks;
        if (v == null) return;
        int y = top + 30;
        if (tab < 4) {
            long s = tab < v.left().length ? v.left()[tab] : -1;
            Ui.text(c, Text.literal(TABS[tab] + " tasks  ·  " + left(s)), left + 10, top + 10, 0.8f, Ui.MUTED, false);
            List<Net.TaskEntry> list = tasks();
            for (int i = scroll; i < list.size() && i < scroll + visible(); i++) {
                Net.TaskEntry t = list.get(i);
                boolean done = t.progress() >= t.goal();
                c.fill(left + 6, y, left + w - 6, y + ROW - 3, t.claimed() ? 0x18000000 : done ? 0x3040E07A : 0x28000000);
                Ui.item(c, BattlePassScreen.icon(t.icon()), left + 12, y + 6, 1.2f);
                Ui.text(c, Ui.heading(t.text()), left + 38, y + 4, 0.9f, t.claimed() ? Ui.DIM : Ui.CREAM, false);
                Ui.bar(c, left + 38, y + 16, w - 160, 4, t.progress() / (float) Math.max(1, t.goal()), done ? 0xFF6FCF5A : Ui.XP);
                Ui.text(c, Text.literal("Reward: " + t.reward()), left + 38, y + 22, 0.6f, Ui.GOLD, false);
                y += ROW;
            }
        } else {
            long earned = v.achievements().stream().filter(Net.AchievementEntry::earned).count();
            Ui.text(c, Text.literal(earned + " / " + v.achievements().size() + " achievements  ·  earned titles show by your name"),
                left + 10, top + 10, 0.8f, Ui.MUTED, false);
            List<Net.AchievementEntry> list = v.achievements();
            for (int i = scroll; i < list.size() && i < scroll + visible(); i++) {
                Net.AchievementEntry a = list.get(i);
                int col = 0xFF000000 | a.color();
                c.fill(left + 6, y, left + w - 6, y + ROW - 3, a.earned() ? 0x30E0B96A : 0x28000000);
                c.fill(left + 6, y, left + 9, y + ROW - 3, a.earned() ? col : 0xFF3A3A34);
                Ui.text(c, Ui.heading(a.title()), left + 16, y + 4, 0.95f, a.earned() ? col : Ui.DIM, false);
                Ui.text(c, Text.literal(a.desc() + "  ·  " + a.reward()), left + 16, y + 15, 0.62f, a.earned() ? Ui.CREAM : Ui.MUTED, false);
                if (!a.earned()) {
                    Ui.bar(c, left + 16, y + 25, w - 140, 3, a.progress() / (float) Math.max(1, a.goal()), Ui.XP);
                    Ui.text(c, Text.literal(String.format(java.util.Locale.ROOT, "%,d / %,d", a.progress(), a.goal())), left + w - 118, y + 22, 0.6f, Ui.MUTED, false);
                }
                y += ROW;
            }
        }
    }
}
