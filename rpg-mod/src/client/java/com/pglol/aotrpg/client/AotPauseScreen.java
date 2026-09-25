package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.text.Text;

/** Pause menu in the AoT style, with shortcuts to the RPG screens. */
public class AotPauseScreen extends Screen {
    public AotPauseScreen() {
        super(Text.literal("Paused"));
    }

    @Override
    protected void init() {
        int bw = 170, bh = 20, gap = 4;
        int x = Math.max(20, width / 2 - bw - 30);
        boolean mods = FabricLoader.getInstance().isModLoaded("modmenu");
        int n = mods ? 9 : 8;
        int y = Math.max(50, height / 2 - (n * (bh + gap)) / 2 + 10);
        boolean hasChar = ClientState.profile != null;
        add(x, y, bw, bh, Ui.title("RESUME"), () -> client.setScreen(null)).textScale = 1.2f;
        y += bh + gap + 4;
        add(x, y, bw, bh, Text.literal("Character & Skills  [K]"), () -> client.setScreen(new CharacterScreen(0))).active = hasChar;
        y += bh + gap;
        add(x, y, bw, bh, Text.literal("Quest Journal  [J]"), () -> client.setScreen(new JournalScreen())).active = hasChar;
        y += bh + gap;
        add(x, y, bw, bh, Text.literal("World Map  [M]"), () -> client.setScreen(new WorldMapScreen()));
        y += bh + gap;
        add(x, y, bw, bh, Text.literal("Satchel  [B]"), () -> {
            client.setScreen(null);
            ClientPlayNetworking.send(new Net.OpenSatchel());
        }).active = hasChar;
        y += bh + gap + 4;
        add(x, y, bw, bh, Text.literal("Options"), () -> client.setScreen(new OptionsScreen(this, client.options)));
        y += bh + gap;
        if (mods) {
            add(x, y, bw, bh, Text.literal("Mods"), this::openModMenu);
            y += bh + gap;
        }
        AotButton leave = add(x, y + 4, bw, bh, Text.literal(client.isInSingleplayer() ? "Save and quit" : "Leave the server"), this::leave);
        leave.accent = Ui.RED;
    }

    private AotButton add(int x, int y, int w, int h, Text label, Runnable r) {
        return addDrawableChild(new AotButton(x, y, w, h, label, r));
    }

    private void openModMenu() {
        try {
            Class<?> c = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
            client.setScreen((Screen) c.getConstructor(Screen.class).newInstance(this));
        } catch (Exception e) {
            com.pglol.aotrpg.AotRpg.LOG.warn("Could not open Mod Menu", e);
        }
    }

    private void leave() {
        boolean single = client.isInSingleplayer();
        if (client.world != null) client.world.disconnect();
        if (single) client.disconnect(new MessageScreen(Text.translatable("menu.savingLevel")));
        else client.disconnect();
        client.setScreen(single ? new TitleScreen() : new MultiplayerScreen(new TitleScreen()));
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xC00A0D0A, 0xE0050605);
        c.fillGradient(0, 0, width / 2, height, 0x40000000, 0x00000000);
        int x = Math.max(20, width / 2 - 200);
        Ui.text(c, Ui.title("ATTACK ON TITAN"), x, 18, 1.8f, Ui.GOLD, false);
        c.drawTextWithShadow(textRenderer, Text.literal("Paused"), x + 2, 38, Ui.MUTED);

        // Character card on the right
        int cw = 190, cx = Math.min(width - cw - 20, width / 2 + 20), cy = height / 2 - 80;
        Ui.panel(c, cx, cy, cw, 160);
        Ui.crest(c, cx + cw / 2 - 24, cy + 8, 48, 1f);
        Net.Sync p = ClientState.profile;
        if (p != null) {
            Ui.text(c, Ui.heading(p.name()), cx + cw / 2f, cy + 60, 1.1f, Ui.CREAM, true);
            Text sub = Text.literal("Level " + p.level() + " ").withColor(Ui.GOLD)
                .append(Text.literal(p.disciplineEnum().title).withColor(Ui.disciplineColor(p.discipline())));
            c.drawCenteredTextWithShadow(textRenderer, sub, cx + cw / 2, cy + 74, 0xFFFFFFFF);
            Ui.bar(c, cx + 16, cy + 86, cw - 32, 4, p.need() > 0 ? (float) p.xp() / p.need() : 1, Ui.XP);
            Ui.divider(c, cx + 12, cy + 98, cw - 24);
            Net.Objective o = ClientState.objective;
            if (o != null) {
                c.drawTextWithShadow(textRenderer, Ui.heading(textRenderer.trimToWidth(o.chapter(), cw - 20)), cx + 10, cy + 106, Ui.GOLD);
                Ui.wrapped(c, Text.literal("▶ " + o.text() + (o.progress().isEmpty() ? "" : "  " + o.progress())), cx + 10, cy + 118, cw - 20, Ui.CREAM);
            }
            int party = ClientState.party.size();
            if (party > 0) c.drawTextWithShadow(textRenderer, Text.literal("Party: " + (party + 1) + " members"), cx + 10, cy + 146, 0xFF5BD35B);
        } else {
            c.drawCenteredTextWithShadow(textRenderer, Text.literal("No character yet"), cx + cw / 2, cy + 70, Ui.MUTED);
        }
    }
}
