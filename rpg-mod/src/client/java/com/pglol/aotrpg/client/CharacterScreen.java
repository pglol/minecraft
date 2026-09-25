package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.Skill;
import com.pglol.aotrpg.Stat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Character sheet (attributes) and the skill tree. Opened with K or /character. */
public class CharacterScreen extends Screen {
    private int tab;
    private int left, top, w, h;

    public CharacterScreen(int tab) {
        super(Text.literal("Character"));
        this.tab = tab;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Called when new data arrives from the server. */
    public void refresh() {
        clearAndInit();
    }

    @Override
    protected void init() {
        Net.Sync p = ClientState.profile;
        if (p == null) return;
        w = Math.min(440, width - 20);
        left = (width - w) / 2;
        top = 64;
        h = Math.min(220, height - top - 10);

        String[] tabs = {"Attributes", "Skills"};
        for (int i = 0; i < tabs.length; i++) {
            int t = i;
            String label = tabs[i] + (i == 0 && p.points() > 0 ? "  •" + p.points() : i == 1 && p.skillPoints() > 0 ? "  •" + p.skillPoints() : "");
            addDrawableChild(new AotButton(left + i * 104, top - 22, 100, 20, Ui.heading(label), () -> {
                tab = t;
                clearAndInit();
            }).selected(tab == i));
        }
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));

        if (tab == 0) {
            for (Stat s : Stat.values()) {
                if (p.points() <= 0) break;
                int y = statY(s.ordinal());
                addDrawableChild(new AotButton(left + w - 32, y + 4, 22, 20, Text.literal("+"),
                    () -> ClientPlayNetworking.send(new Net.SpendPoint(s.ordinal()))));
            }
        } else {
            for (Skill s : Skill.values()) addDrawableChild(new SkillNode(s, nodeX(s), nodeY(s)));
        }
    }

    private int statY(int i) {
        return top + 30 + i * Math.min(34, (h - 40) / 4);
    }

    private int nodeX(Skill s) {
        int colW = w / 3;
        return left + s.branch.ordinal() * colW + colW / 2 - 13;
    }

    private int nodeY(Skill s) {
        int step = Math.min(46, (h - 40) / 4);
        return top + 32 + s.tier * step;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Net.Sync p = ClientState.profile;
        if (p == null) return;

        // Header
        Ui.text(c, Ui.title(p.name()), width / 2f, 8, 1.8f, Ui.GOLD, true);
        Text sub = Text.literal("Level " + p.level() + " ").withColor(Ui.CREAM)
            .append(Text.literal(p.disciplineEnum().title).withColor(Ui.disciplineColor(p.discipline())))
            .append(Text.literal("  ·  " + p.originEnum().title + "  ·  Titans slain: " + p.titanKills()).withColor(Ui.MUTED));
        c.drawCenteredTextWithShadow(textRenderer, sub, width / 2, 28, 0xFFFFFFFF);
        int bw = 220;
        Ui.bar(c, width / 2 - bw / 2, 40, bw, 7, p.need() > 0 ? (float) p.xp() / p.need() : 1, Ui.XP);
        String xp = p.level() >= 100 ? "MAX LEVEL" : p.xp() + " / " + p.need() + " XP";
        c.drawCenteredTextWithShadow(textRenderer, Text.literal(xp), width / 2, 50, Ui.MUTED);

        Ui.panel(c, left, top, w, h);
        if (tab == 0) drawAttributes(c, p);
        else drawSkills(c, p);
    }

    private void drawAttributes(DrawContext c, Net.Sync p) {
        c.drawTextWithShadow(textRenderer, Ui.heading("Attributes"), left + 10, top + 9, Ui.GOLD);
        String pts = p.points() + " point" + (p.points() == 1 ? "" : "s") + " to spend";
        c.drawTextWithShadow(textRenderer, Text.literal(pts), left + w - 10 - textRenderer.getWidth(pts), top + 9,
            p.points() > 0 ? Ui.GOLD : Ui.MUTED);
        Ui.divider(c, left + 10, top + 22, w - 20);
        for (Stat s : Stat.values()) {
            int y = statY(s.ordinal());
            int total = p.total()[s.ordinal()], base = p.base()[s.ordinal()];
            c.drawItem(new ItemStack(s.icon), left + 12, y + 6);
            c.drawTextWithShadow(textRenderer, Ui.heading(s.title), left + 34, y + 5, Ui.CREAM);
            c.drawTextWithShadow(textRenderer, Text.literal(effect(s, total)), left + 34, y + 16, 0xFF8FCB6A);
            Ui.text(c, Ui.title(String.valueOf(total)), left + w - 60, y + 8, 1.4f, Ui.GOLD, true);
            if (total != base) c.drawTextWithShadow(textRenderer, Text.literal("(+" + (total - base) + ")"), left + w - 100, y + 10, Ui.MUTED);
        }
        // Derived values from the live player
        var pl = MinecraftClient.getInstance().player;
        if (pl != null) {
            int y = top + h - 16;
            String line = String.format(Locale.ROOT, "Health %d   Armor %d   Damage %.1f   Speed %.0f%%   Stamina %d",
                Math.round(pl.getMaxHealth()), pl.getArmor(),
                pl.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE),
                pl.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) / 0.1 * 100,
                Math.round(ClientState.maxStamina));
            c.fill(left + 1, y - 4, left + w - 1, top + h - 1, 0x40000000);
            c.drawCenteredTextWithShadow(textRenderer, Text.literal(line), left + w / 2, y, Ui.MUTED);
        }
    }

    private static String effect(Stat s, int v) {
        return switch (s) {
            case STRENGTH -> String.format(Locale.ROOT, "+%.1f melee damage", 0.5 * v);
            case AGILITY -> String.format(Locale.ROOT, "+%.1f%% movement speed", 1.5 * v);
            case ENDURANCE -> "+" + (2 * v) + " health, +" + (5 * v) + " stamina";
            case RESOLVE -> "+" + v + " armor";
        };
    }

    private void drawSkills(DrawContext c, Net.Sync p) {
        String pts = p.skillPoints() + " skill point" + (p.skillPoints() == 1 ? "" : "s");
        c.drawTextWithShadow(textRenderer, Text.literal(pts), left + w - 10 - textRenderer.getWidth(pts), top + 9,
            p.skillPoints() > 0 ? Ui.GOLD : Ui.MUTED);
        c.drawTextWithShadow(textRenderer, Ui.heading("Skills"), left + 10, top + 9, Ui.GOLD);
        int colW = w / 3;
        for (Skill.Branch b : Skill.Branch.values()) {
            int cx = left + b.ordinal() * colW + colW / 2;
            Text t = Ui.heading(b.title);
            c.drawTextWithShadow(textRenderer, t, cx - textRenderer.getWidth(t) / 2, top + 20, b.color);
            if (b.ordinal() > 0) c.fill(left + b.ordinal() * colW, top + 18, left + b.ordinal() * colW + 1, top + h - 8, 0x307A6139);
            // connectors
            for (int tier = 1; tier < 4; tier++) {
                Skill s = Skill.of(b, tier), prev = Skill.of(b, tier - 1);
                int y0 = nodeY(prev) + 26, y1 = nodeY(s);
                c.fill(cx - 1, y0, cx + 1, y1, p.has(s) ? b.color : p.has(prev) ? 0xA0B8955A : 0x40FFFFFF);
            }
        }
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (tab == 1) {
            for (var el : children()) {
                if (el instanceof SkillNode n && n.isHovered()) c.drawTooltip(textRenderer, n.tooltip(), mouseX, mouseY);
            }
        }
    }

    /** One skill in the tree. */
    private final class SkillNode extends PressableWidget {
        private final Skill skill;

        SkillNode(Skill skill, int x, int y) {
            super(x, y, 26, 26, Text.literal(skill.title));
            this.skill = skill;
        }

        private boolean learned() {
            return ClientState.profile.has(skill);
        }

        private boolean available() {
            Net.Sync p = ClientState.profile;
            Skill prev = skill.previous();
            return !learned() && p.skillPoints() > 0 && p.level() >= skill.level && (prev == null || p.has(prev));
        }

        List<Text> tooltip() {
            Net.Sync p = ClientState.profile;
            List<Text> l = new ArrayList<>();
            l.add(Text.literal(skill.title).formatted(Formatting.GOLD, Formatting.BOLD));
            l.add(Text.literal(skill.branch.title + " · Tier " + (skill.tier + 1)).formatted(Formatting.DARK_GRAY));
            l.add(Text.literal(skill.effect).formatted(Formatting.GREEN));
            if (learned()) {
                l.add(Text.literal("Learned").formatted(Formatting.GOLD));
                return l;
            }
            l.add(Text.literal("Requires level " + skill.level).formatted(p.level() >= skill.level ? Formatting.GRAY : Formatting.RED));
            Skill prev = skill.previous();
            if (prev != null) l.add(Text.literal("Requires " + prev.title).formatted(p.has(prev) ? Formatting.GRAY : Formatting.RED));
            l.add(available() ? Text.literal("Click to learn (1 skill point)").formatted(Formatting.YELLOW)
                : Text.literal("Costs 1 skill point").formatted(p.skillPoints() > 0 ? Formatting.GRAY : Formatting.RED));
            return l;
        }

        @Override
        public void onPress() {
            if (available()) ClientPlayNetworking.send(new Net.Learn(skill.ordinal()));
        }

        @Override
        public void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY();
            boolean learned = learned(), avail = available();
            int border = learned ? skill.branch.color : avail ? Ui.GOLD : 0xFF3A3830;
            c.fill(x, y, x + 26, y + 26, learned ? 0xF0222A20 : 0xE0101310);
            c.drawBorder(x, y, 26, 26, border);
            if (learned) c.drawBorder(x + 1, y + 1, 24, 24, 0x80E0B96A);
            if (avail) {
                int a = (int) (90 + 80 * Math.sin(Util.getMeasuringTimeMs() / 250.0));
                c.drawBorder(x - 2, y - 2, 30, 30, (a << 24) | 0xE0B96A);
            }
            if (isHovered()) c.fill(x + 1, y + 1, x + 25, y + 25, 0x30FFFFFF);
            c.drawItem(new ItemStack(skill.icon), x + 5, y + 5);
            if (!learned && !avail) c.fill(x + 1, y + 1, x + 25, y + 25, 0x90000000);
            Text name = Text.literal(skill.title);
            int tw = textRenderer.getWidth(name);
            c.drawTextWithShadow(textRenderer, name, x + 13 - tw / 2, y + 28, learned ? Ui.CREAM : avail ? Ui.GOLD : Ui.DIM);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder b) {
            appendDefaultNarrations(b);
        }
    }
}
