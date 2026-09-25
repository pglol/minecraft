package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/** The battle pass: a scrolling season track with a free and a premium row. Click a reward to claim its tier. */
public final class BattlePassScreen extends Screen {
    private static final int COL = 58, BOX = 40;
    private int left, top, w, h, scroll = -1;

    public BattlePassScreen() {
        super(Text.literal("Battle Pass"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    static ItemStack icon(String id) {
        Identifier i = Identifier.tryParse(id);
        var item = i == null ? Items.PAPER : Registries.ITEM.get(i);
        return new ItemStack(item == Items.AIR ? Items.PAPER : item);
    }

    private int tier(Net.PassView v) {
        return (int) Math.min(v.tiers().size(), v.xp() / Math.max(1, v.xpPerTier()));
    }

    private int visible() {
        return Math.max(1, (w - 70) / COL);
    }

    @Override
    protected void init() {
        w = Math.min(640, width - 20);
        h = 190;
        left = (width - w) / 2;
        top = Math.max(50, (height - h) / 2);
        Net.PassView v = ClientState.pass;
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        if (v == null) return;
        int max = Math.max(0, v.tiers().size() - visible());
        if (scroll < 0) scroll = Math.max(0, Math.min(max, tier(v) - 1));
        scroll = Math.max(0, Math.min(max, scroll));
        addDrawableChild(new AotButton(left + 4, top + 88, 18, 40, Text.literal("<"), () -> {
            scroll -= visible() - 1;
            clearAndInit();
        })).active = scroll > 0;
        addDrawableChild(new AotButton(left + w - 22, top + 88, 18, 40, Text.literal(">"), () -> {
            scroll += visible() - 1;
            clearAndInit();
        })).active = scroll < max;
        boolean any = false;
        for (int t = 0; t < tier(v); t++) {
            Net.PassTier pt = v.tiers().get(t);
            if (!pt.freeClaimed() || (v.premium() && !pt.premiumClaimed())) any = true;
        }
        addDrawableChild(new AotButton(left + w - 250, top + h - 26, 110, 20, Ui.heading("Claim all"),
            () -> ClientPlayNetworking.send(new Net.PassAction("claimall", 0)))).active = any;
        AotButton buy = addDrawableChild(new AotButton(left + w - 134, top + h - 26, 128, 20,
            Ui.heading(v.premium() ? "Premium active" : "Premium · " + v.premiumGold() + " Gold"),
            () -> ClientPlayNetworking.send(new Net.PassAction("buy", 0))));
        buy.selected(v.premium());
        buy.active = !v.premium() && !v.ended() && ClientState.gold >= v.premiumGold();
    }

    private int boxAt(double mx, double my, boolean[] premium) {
        Net.PassView v = ClientState.pass;
        if (v == null) return -1;
        int x0 = left + 30;
        for (int i = 0; i < visible() && scroll + i < v.tiers().size(); i++) {
            int x = x0 + i * COL + (COL - BOX) / 2;
            if (mx >= x && mx < x + BOX) {
                if (my >= top + 60 && my < top + 60 + BOX) {
                    premium[0] = false;
                    return scroll + i;
                }
                if (my >= top + 112 && my < top + 112 + BOX) {
                    premium[0] = true;
                    return scroll + i;
                }
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boolean[] prem = new boolean[1];
        int t = boxAt(mx, my, prem);
        Net.PassView v = ClientState.pass;
        if (t >= 0 && v != null && t < tier(v)) {
            ClientPlayNetworking.send(new Net.PassAction("claim", t));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        scroll -= (int) Math.signum(vAmount);
        clearAndInit();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("BATTLE PASS"), width / 2f, top - 42, 1.4f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.PassView v = ClientState.pass;
        if (v == null) return;
        int tier = tier(v);
        Ui.text(c, Ui.heading(v.name()), left + 10, top + 8, 1f, Ui.CREAM, false);
        String ends = v.ended() ? "Season ended" : v.endsAt() > 0 ? "Ends in " + days(v.endsAt()) : "";
        if (!ends.isEmpty()) Ui.text(c, Text.literal(ends), left + w - 10 - Ui.font().getWidth(ends) * 0.7f, top + 10, 0.7f, v.ended() ? Ui.RED : Ui.MUTED, false);
        boolean maxed = tier >= v.tiers().size();
        long into = maxed ? v.xpPerTier() : v.xp() % v.xpPerTier();
        Ui.text(c, Text.literal("Tier " + tier + " / " + v.tiers().size()), left + 10, top + 24, 0.8f, Ui.GOLD, false);
        Ui.bar(c, left + 80, top + 25, w - 200, 6, into / (float) v.xpPerTier(), Ui.XP);
        Ui.text(c, Text.literal(maxed ? "Complete" : into + " / " + v.xpPerTier() + " XP"), left + w - 112, top + 24, 0.7f, Ui.MUTED, false);
        Ui.text(c, Text.literal("FREE"), left + 8, top + 76, 0.6f, Ui.CREAM, false);
        Ui.text(c, Text.literal("PREMIUM"), left + 8, top + 128, 0.5f, Ui.GOLD, false);
        int x0 = left + 30;
        List<Net.PassTier> tiers = v.tiers();
        Net.PassTier hover = null;
        boolean hoverPrem = false;
        for (int i = 0; i < visible() && scroll + i < tiers.size(); i++) {
            int t = scroll + i;
            Net.PassTier pt = tiers.get(t);
            int cx = x0 + i * COL, x = cx + (COL - BOX) / 2;
            boolean reached = t < tier;
            Ui.text(c, Text.literal(String.valueOf(t + 1)), cx + COL / 2f, top + 46, 0.8f, reached ? Ui.GOLD : Ui.DIM, true);
            c.fill(cx + 4, top + 56, cx + COL - 4, top + 57, reached ? Ui.GOLD : 0x40FFFFFF);
            drawBox(c, x, top + 60, pt.freeIcon(), pt.freeColor(), reached, pt.freeClaimed(), true);
            drawBox(c, x, top + 112, pt.premiumIcon(), pt.premiumColor(), reached, pt.premiumClaimed(), v.premium());
            if (mouseX >= x && mouseX < x + BOX) {
                if (mouseY >= top + 60 && mouseY < top + 60 + BOX) hover = pt;
                if (mouseY >= top + 112 && mouseY < top + 112 + BOX) {
                    hover = pt;
                    hoverPrem = true;
                }
            }
        }
        Ui.text(c, Text.literal("Earn pass XP from titans, quests, work orders, crafting, and a daily login bonus."),
            left + 10, top + h - 20, 0.6f, Ui.MUTED, false);
        if (hover != null) {
            String what = hoverPrem ? hover.premium() : hover.free();
            boolean claimed = hoverPrem ? hover.premiumClaimed() : hover.freeClaimed();
            String state = claimed ? "Claimed" : hoverPrem && !v.premium() ? "Premium only" : "";
            c.drawTooltip(textRenderer, state.isEmpty() ? List.of(Text.literal(what)) : List.of(Text.literal(what), Text.literal(state).withColor(Ui.MUTED)), mouseX, mouseY);
        }
    }

    private void drawBox(DrawContext c, int x, int y, String icon, int color, boolean reached, boolean claimed, boolean owned) {
        int glow = color != 0 ? 0xFF000000 | color : Ui.TRIM;
        boolean ready = reached && owned && !claimed;
        c.fill(x, y, x + BOX, y + BOX, ready ? 0x60E0B96A : claimed ? 0x30000000 : 0x50000000);
        Ui.border(c, x, y, BOX, BOX);
        if (color != 0) c.fill(x + 1, y + BOX - 3, x + BOX - 1, y + BOX - 1, glow);
        Ui.item(c, icon(icon), x + BOX / 2 - 12, y + BOX / 2 - 12, 1.5f);
        if (claimed) {
            c.fill(x + 1, y + 1, x + BOX - 1, y + BOX - 1, 0x90000000);
            Ui.text(c, Text.literal("✔"), x + BOX / 2f, y + BOX / 2f - 5, 1.2f, Ui.STAMINA, true);
        } else if (!owned) {
            c.fill(x + 1, y + 1, x + BOX - 1, y + BOX - 1, 0x70000000);
            // A small padlock.
            int lx = x + BOX - 11, ly = y + 4;
            c.fill(lx + 1, ly, lx + 6, ly + 1, Ui.GOLD);
            c.fill(lx + 1, ly, lx + 2, ly + 4, Ui.GOLD);
            c.fill(lx + 5, ly, lx + 6, ly + 4, Ui.GOLD);
            c.fill(lx, ly + 4, lx + 7, ly + 9, Ui.GOLD);
        } else if (!reached) {
            c.fill(x + 1, y + 1, x + BOX - 1, y + BOX - 1, 0x50000000);
        }
    }

    static String days(long endsAt) {
        long ms = endsAt - System.currentTimeMillis();
        if (ms <= 0) return "0d";
        long d = ms / 86_400_000L, hrs = ms / 3_600_000L % 24;
        return d > 0 ? d + "d " + hrs + "h" : hrs + "h";
    }
}
