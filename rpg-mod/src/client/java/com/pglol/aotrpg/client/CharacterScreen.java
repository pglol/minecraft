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
    private boolean confirmReset;
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
        top = 86;
        h = Math.min(tab == 0 ? 300 : 392, height - top - 8);

        String[] tabs = {"Attributes", "Skills", "Roles"};
        for (int i = 0; i < tabs.length; i++) {
            int t = i;
            String label = tabs[i] + (i == 0 && p.points() > 0 ? "  •" + p.points() : i >= 1 && p.skillPoints() > 0 ? "  •" + p.skillPoints() : "");
            addDrawableChild(new AotButton(left + i * 104, top - 22, 100, 20, Ui.heading(label), () -> {
                tab = t;
                clearAndInit();
            }).selected(tab == i));
        }
        // Cosmetics live with your character now.
        addDrawableChild(new AotButton(left + tabs.length * 104, top - 22, 100, 20, Ui.heading("Cosmetics"),
            () -> client.setScreen(new CosmeticsScreen())));
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));

        if (tab == 0) {
            for (Stat s : Stat.values()) {
                if (p.points() <= 0) break;
                int y = statY(s.ordinal());
                addDrawableChild(new AotButton(left + w - 32, y + 4, 22, 20, Text.literal("+"),
                    () -> ClientPlayNetworking.send(new Net.SpendPoint(s.ordinal()))));
            }
        } else {
            for (Skill s : Skill.values()) if (shown(s.branch)) addDrawableChild(new SkillNode(s, nodeX(s), nodeY(s)));
            if (tab == 2) {
                int colW = w / 4;
                for (com.pglol.aotrpg.PlayerClass rc : com.pglol.aotrpg.PlayerClass.values()) {
                    boolean mine = p.role() == rc;
                    AotButton b = addDrawableChild(new AotButton(left + rc.ordinal() * colW + 4, top + 6, colW - 8, 18,
                        Text.literal(rc.tag() + " " + rc.title).withColor(rc.color), () -> {
                            if (ClientState.profile.role() != rc) ClientPlayNetworking.send(new Net.ChooseRole(rc.ordinal()));
                        }).selected(mine));
                    b.accent = rc.color;
                }
            }
            long cost = Skill.resetCost(p.skillResets());
            int left3 = Skill.MAX_RESETS - p.skillResets();
            AotButton reset = addDrawableChild(new AotButton(left + 8, top + h - 24, 190, 18,
                Text.literal(left3 <= 0 ? "No resets left" : "Reset skills · " + (cost == 0 ? "free" : cost + " Marks") + " · " + left3 + " left"),
                () -> {
                    if (confirmReset) {
                        confirmReset = false;
                        ClientPlayNetworking.send(new Net.SkillReset());
                    } else {
                        confirmReset = true;
                        clearAndInit();
                    }
                }));
            if (confirmReset) reset.setMessage(Text.literal("Click again to reset (" + (cost == 0 ? "free" : cost + " Marks") + ")"));
            reset.accent = Ui.RED;
            reset.active = left3 > 0 && p.anySkill();
        }
    }

    private int statY(int i) {
        return top + 28 + i * 30;
    }

    /** Skills tab: the general trees; Roles tab: the four class trees. */
    private boolean shown(Skill.Branch b) {
        return tab == 1 ? b.cls == null : tab == 2 && b.cls != null;
    }

    private int columns() {
        return tab == 2 ? 4 : 3;
    }

    private int column(Skill.Branch b) {
        return tab == 2 ? b.cls.ordinal() : b.ordinal();
    }

    private int treeTop() {
        return top + (tab == 2 ? 52 : 34);
    }

    private int step() {
        return Math.max(24, Math.min(42, (top + h - (tab == 2 ? 72 : 44) - treeTop()) / Skill.TIERS));
    }

    private int nodeX(Skill s) {
        int colW = w / columns();
        return left + column(s.branch) * colW + colW / 2 - 11 + s.lane * (tab == 2 ? 30 : 40);
    }

    private int nodeY(Skill s) {
        return treeTop() + s.tier * step();
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Net.Sync p = ClientState.profile;
        if (p == null) return;

        // Header
        Ui.text(c, Ui.title(p.name()), width / 2f, 6, 1.8f, Ui.GOLD, true);
        Text sub = Text.literal("Level " + p.level() + " ").withColor(Ui.CREAM)
            .append(Text.literal(p.role().tag() + " " + p.role().title).withColor(p.role().color))
            .append(Text.literal("  ·  " + p.originEnum().title + "  ·  Titans slain: " + p.titanKills()).withColor(Ui.MUTED));
        c.drawCenteredTextWithShadow(textRenderer, sub, width / 2, 26, 0xFFFFFFFF);
        int bw = 220;
        Ui.bar(c, width / 2 - bw / 2, 38, bw, 6, p.need() > 0 ? (float) p.xp() / p.need() : 1, Ui.XP);
        String xp = p.level() >= 100 ? "MAX LEVEL" : p.xp() + " / " + p.need() + " XP";
        c.drawCenteredTextWithShadow(textRenderer, Text.literal(xp), width / 2, 47, Ui.MUTED);

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
        // Crafts: raised by practice (the forge, fishing and cooking minigames), not points.
        int cy = statY(Stat.values().length) + 6;
        Ui.divider(c, left + 10, cy - 4, w - 20);
        c.drawTextWithShadow(textRenderer, Ui.heading("Crafts"), left + 10, cy, Ui.GOLD);
        c.drawTextWithShadow(textRenderer, Text.literal("raised by practice"), left + 60, cy, Ui.MUTED);
        String[] names = {"Smithing", "Fishing", "Cooking"};
        ItemStack[] icons = {new ItemStack(net.minecraft.item.Items.ANVIL), new ItemStack(net.minecraft.item.Items.FISHING_ROD), new ItemStack(net.minecraft.item.Items.CAMPFIRE)};
        String[] perks = {"better forge odds and quality", "shorter waits, better catches", "stronger meals, extra portions"};
        int[] crafts = p.crafts();
        int colW = (w - 20) / 3;
        for (int i = 0; i < names.length && i < crafts.length; i++) {
            int x = left + 10 + i * colW, y = cy + 13;
            int lv = crafts[i] / 1000;
            c.drawItem(icons[i], x, y);
            c.drawTextWithShadow(textRenderer, Ui.heading(names[i] + " " + lv), x + 20, y, Ui.CREAM);
            Ui.bar(c, x + 20, y + 11, colW - 30, 3, (crafts[i] % 1000) / 1000f, Ui.XP);
            Ui.text(c, Text.literal(perks[i]), x + 20, y + 16, 0.55f, Ui.MUTED, false);
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
            case CHARISMA -> (2 * v) + "% better prices from merchants";
        };
    }

    private void drawSkills(DrawContext c, Net.Sync p) {
        String pts = p.skillPoints() + " skill point" + (p.skillPoints() == 1 ? "" : "s");
        c.drawTextWithShadow(textRenderer, Text.literal(pts), left + w - 10 - textRenderer.getWidth(pts), top + 9,
            p.skillPoints() > 0 ? Ui.GOLD : Ui.MUTED);
        if (tab == 1) c.drawTextWithShadow(textRenderer, Ui.heading("Skills"), left + 10, top + 9, Ui.GOLD);
        int colW = w / columns();
        for (Skill.Branch b : Skill.Branch.values()) {
            if (!shown(b)) continue;
            int col = column(b);
            int cx = left + col * colW + colW / 2;
            Text t = Ui.heading(b.title);
            if (tab == 2) {
                String sub = b.cls.role + (p.role() == b.cls ? " · your role" : "");
                Ui.text(c, Text.literal(sub), cx, top + 29, 0.6f, p.role() == b.cls ? Ui.GOLD : Ui.MUTED, true);
            } else {
                c.drawTextWithShadow(textRenderer, t, cx - textRenderer.getWidth(t) / 2, top + 20, b.color);
            }
            if (col > 0) c.fill(left + col * colW, top + 18, left + col * colW + 1, top + h - 30, 0x307A6139);
            // Connectors from each skill to the ones below it (forks branch out and join again).
            for (int tier = 1; tier < Skill.TIERS; tier++) {
                for (Skill s : Skill.at(b, tier)) {
                    for (Skill prev : Skill.at(b, tier - 1)) {
                        int x0 = nodeX(prev) + 11, y0 = nodeY(prev) + 22, x1 = nodeX(s) + 11, y1 = nodeY(s);
                        int col = p.has(s) && p.has(prev) ? b.color : p.has(prev) ? 0xA0B8955A : 0x30FFFFFF;
                        int ym = (y0 + y1) / 2;
                        c.fill(x0 - 1, y0, x0 + 1, ym, col);
                        c.fill(Math.min(x0, x1) - 1, ym - 1, Math.max(x0, x1) + 1, ym + 1, col);
                        c.fill(x1 - 1, ym, x1 + 1, y1, col);
                    }
                }
            }
            // Points spent in this branch.
            int spent = 0;
            for (Skill s : Skill.values()) if (s.branch == b && p.has(s)) spent += s.cost;
            if (spent > 0 && tab == 1) {
                String sp = spent + " pts";
                c.drawTextWithShadow(textRenderer, Text.literal(sp), cx - textRenderer.getWidth(sp) / 2, top + h - 38, b.color);
            }
        }
        if (tab == 2) {
            com.pglol.aotrpg.PlayerClass r = p.role();
            String line = r.blurb + "  Z " + r.abilities[0] + " · X " + r.abilities[1] + " · V " + r.abilities[2];
            Ui.text(c, Text.literal("Every tree is open to you. Your role decides your Z / X / V abilities and what others see."),
                left + w / 2f, top + h - 44, 0.55f, Ui.MUTED, true);
            Ui.text(c, Text.literal(line), left + w / 2f, top + h - 36, 0.55f, r.color, true);
        }
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (tab >= 1) {
            for (var el : children()) {
                if (el instanceof SkillNode n && n.isHovered()) c.drawTooltip(textRenderer, n.tooltip(), mouseX, mouseY);
            }
        }
    }

    /** One skill in the tree. */
    private final class SkillNode extends PressableWidget {
        private final Skill skill;

        SkillNode(Skill skill, int x, int y) {
            super(x, y, 22, 22, Text.literal(skill.title));
            this.skill = skill;
        }

        private boolean learned() {
            return ClientState.profile.has(skill);
        }

        private boolean available() {
            Net.Sync p = ClientState.profile;
            return skill.blocked(p.level(), p.skillPoints(), p::has) == null;
        }

        List<Text> tooltip() {
            Net.Sync p = ClientState.profile;
            List<Text> l = new ArrayList<>();
            l.add(Text.literal(skill.title).formatted(Formatting.GOLD, Formatting.BOLD));
            l.add(Text.literal(skill.branch.title + " · Tier " + (skill.tier + 1)).formatted(Formatting.DARK_GRAY));
            l.add(Text.literal(skill.effect).formatted(Formatting.GREEN));
            if (skill.slot() >= 0 && skill.branch.cls != p.role()) {
                l.add(Text.literal("On your keys while your role is " + skill.branch.cls.title).formatted(Formatting.DARK_AQUA));
            }
            if (learned()) {
                l.add(Text.literal("Learned").formatted(Formatting.GOLD));
                return l;
            }
            l.add(Text.literal("Requires level " + skill.level).formatted(p.level() >= skill.level ? Formatting.GRAY : Formatting.RED));
            if (skill.tier > 0) {
                boolean ok = skill.unlockedBy(p::has);
                StringBuilder req = new StringBuilder("Requires ");
                java.util.List<Skill> above = Skill.at(skill.branch, skill.tier - 1);
                for (int i = 0; i < above.size(); i++) req.append(i == 0 ? "" : " or ").append(above.get(i).title);
                l.add(Text.literal(req.toString()).formatted(ok ? Formatting.GRAY : Formatting.RED));
            }
            String cost = skill.cost + " skill point" + (skill.cost == 1 ? "" : "s");
            l.add(available() ? Text.literal("Click to learn (" + cost + ")").formatted(Formatting.YELLOW)
                : Text.literal("Costs " + cost).formatted(p.skillPoints() >= skill.cost ? Formatting.GRAY : Formatting.RED));
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
            c.fill(x, y, x + 22, y + 22, learned ? 0xF0222A20 : 0xE0101310);
            c.drawBorder(x, y, 22, 22, border);
            if (learned) c.drawBorder(x + 1, y + 1, 20, 20, 0x80E0B96A);
            if (avail) {
                int a = (int) (90 + 80 * Math.sin(Util.getMeasuringTimeMs() / 250.0));
                c.drawBorder(x - 2, y - 2, 26, 26, (a << 24) | 0xE0B96A);
            }
            if (isHovered()) c.fill(x + 1, y + 1, x + 21, y + 21, 0x30FFFFFF);
            c.drawItem(new ItemStack(skill.icon), x + 3, y + 3);
            if (!learned && !avail) c.fill(x + 1, y + 1, x + 21, y + 21, 0x90000000);
            // Cost pip and a small name.
            Ui.text(c, Text.literal(String.valueOf(skill.cost)), x + 19, y + 14, 0.6f, learned ? Ui.GOLD : Ui.MUTED, false);
            if (skill.effect.contains("\u2726")) Ui.text(c, Text.literal("\u2726"), x + 1, y + 1, 0.6f, 0xFFFFD24A, false);
            Text name = Text.literal(skill.title);
            Ui.text(c, name, x + 11, y + 23, 0.55f, learned ? Ui.CREAM : avail ? Ui.GOLD : Ui.DIM, true);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder b) {
            appendDefaultNarrations(b);
        }
    }
}
