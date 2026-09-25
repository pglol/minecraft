package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.Locale;

/** The home forge: upgrade gear or forge new gear, each through a quick strike minigame. */
public final class ForgeScreen extends Screen {
    private static int tab;
    private int left, top, w, h;

    public ForgeScreen() {
        super(Text.literal("Forge"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private void strike(String title, float difficulty, java.util.function.Consumer<Float> then) {
        client.setScreen(new MinigameScreen(MinigameScreen.Kind.STRIKE, title, "Strike in the bright zone: Space or click, three times", difficulty, q -> {
            then.accept(q);
            client.setScreen(this);
        }));
    }

    @Override
    protected void init() {
        w = Math.min(460, width - 20);
        h = Math.min(280, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 10);
        addDrawableChild(new AotButton(left, top - 22, 110, 20, Ui.heading("Upgrade"), () -> {
            tab = 0;
            clearAndInit();
        })).selected(tab == 0);
        addDrawableChild(new AotButton(left + 114, top - 22, 110, 20, Ui.heading("Forge new"), () -> {
            tab = 1;
            clearAndInit();
        })).selected(tab == 1);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        Net.ForgeView v = ClientState.forge;
        if (v == null) return;
        int y = top + 30;
        if (tab == 0) {
            for (Net.ForgeGear g : v.gear()) {
                if (y > top + h - 26) break;
                AotButton b = addDrawableChild(new AotButton(left + w - 100, y + 2, 90, 18, Text.literal(g.up() >= 10 ? "Maxed" : "Strike +" + (g.up() + 1)),
                    () -> strike("Upgrade", Math.min(1, g.up() / 10f), q -> ClientPlayNetworking.send(new Net.ForgeAction("upgrade", g.slot(), "", q)))));
                b.active = g.up() < 10;
                y += 24;
            }
        } else {
            for (Net.ForgeRecipe r : v.recipes()) {
                if (y > top + h - 26) break;
                AotButton b = addDrawableChild(new AotButton(left + w - 100, y + 2, 90, 18, Text.literal("Forge"),
                    () -> strike("Forge " + r.title(), 0.4f, q -> ClientPlayNetworking.send(new Net.ForgeAction("craft", 0, r.id(), q)))));
                b.active = r.ready();
                y += 30;
            }
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("FORGE"), width / 2f, top - 40, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.ForgeView v = ClientState.forge;
        if (v == null || client.player == null) return;
        Ui.text(c, Text.literal("Smithing " + v.smithing() + "  ·  Iron " + v.iron() + "  ·  Ultrahard steel " + v.steel()
            + String.format(Locale.ROOT, "  ·  %,d Marks", ClientState.marks)), left + 10, top + 10, 0.75f, Ui.CREAM, false);
        int y = top + 30;
        if (tab == 0) {
            if (v.gear().isEmpty()) Ui.text(c, Text.literal("No gear in your backpack to upgrade."), left + 12, y + 6, 0.8f, Ui.MUTED, false);
            for (Net.ForgeGear g : v.gear()) {
                if (y > top + h - 26) break;
                ItemStack s = client.player.getInventory().main.get(g.slot());
                GearUi.backing(c, s, left + 10, y + 3);
                c.drawItem(s, left + 10, y + 3);
                Ui.text(c, s.getName(), left + 32, y + 2, 0.85f, Ui.CREAM, false);
                String cost = g.marks() + " Marks · " + g.iron() + " iron" + (g.steel() > 0 ? " · " + g.steel() + " ultrahard steel" : "")
                    + " · " + Math.round(g.chance() * 100) + "%+ chance";
                Ui.text(c, Text.literal(cost), left + 32, y + 12, 0.6f, Ui.MUTED, false);
                if (mouseX >= left + 10 && mouseX < left + 26 && mouseY >= y + 3 && mouseY < y + 19) c.drawItemTooltip(textRenderer, s, mouseX, mouseY);
                y += 24;
            }
        } else {
            for (Net.ForgeRecipe r : v.recipes()) {
                if (y > top + h - 26) break;
                Ui.text(c, Ui.heading(r.title()), left + 12, y + 2, 0.95f, r.ready() ? Ui.CREAM : Ui.DIM, false);
                Ui.text(c, Text.literal(r.marks() + " Marks · " + r.materials()), left + 12, y + 14, 0.6f, r.ready() ? Ui.MUTED : Ui.RED, false);
                y += 30;
            }
            Ui.text(c, Text.literal("Great strikes and a higher smithing level forge rarer gear."), left + 12, top + h - 12, 0.65f, Ui.MUTED, false);
        }
    }
}
