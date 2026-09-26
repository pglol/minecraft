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

    private static final int TW = 120, GAP = 4;
    private int gridX, gridW;

    @Override
    protected void init() {
        headers.clear();
        headerNames.clear();
        if (ClientState.profile != null) ClientPlayNetworking.send(new Net.FactionAction("view", ""));
        boolean mods = FabricLoader.getInstance().isModLoaded("modmenu");
        boolean op = client.player != null && client.player.hasPermissionLevel(2);
        boolean hasChar = ClientState.profile != null;
        boolean war = ClientState.factions != null && !ClientState.factions.event().isEmpty();
        gridW = 3 * TW + 2 * GAP;
        gridX = Math.max(12, Math.min(width / 2 - gridW + 40, width - gridW - 12));
        // Rows: resume, 4 sections (2 rows each), system; shrink the buttons on short screens.
        int bh = height >= 330 ? 20 : 18;
        int total = (bh + 6) + 4 * 11 + 7 * (bh + GAP) + (bh + 8);
        int y = Math.max(48, (height - total) / 2 + 12);
        int x = gridX;

        add(x, y, gridW, bh, Ui.title("RESUME"), () -> client.setScreen(null)).textScale = 1.2f;
        y += bh + 6;

        y = section(y, bh, "Character", new Tile[] {
            new Tile("Character [K]", "minecraft:writable_book", hasChar, () -> client.setScreen(new CharacterScreen(0))),
            new Tile("Satchel [B]", "minecraft:bundle", hasChar, () -> ClientPlayNetworking.send(new Net.OpenSatchel())),
            new Tile("Characters", "minecraft:armor_stand", true, () -> ClientPlayNetworking.send(new Net.CharacterAction("list", 0))),
            new Tile("Game Mode", "minecraft:compass", hasChar, () -> ClientPlayNetworking.send(new Net.ModeAction("open")))});
        y = section(y, bh, "Adventure", new Tile[] {
            new Tile("Journal [J]", "minecraft:book", hasChar, () -> client.setScreen(new JournalScreen())),
            new Tile("World Map [M]", "minecraft:filled_map", true, () -> client.setScreen(new WorldMapScreen())),
            new Tile("Tasks & Titles", "minecraft:target", hasChar, () -> ClientPlayNetworking.send(new Net.TaskAction("open", ""))),
            new Tile("Battle Pass", "minecraft:nether_star", hasChar, () -> ClientPlayNetworking.send(new Net.PassAction("open", 0))),
            new Tile("Events", "minecraft:firework_rocket", hasChar, () -> ClientPlayNetworking.send(new Net.EventAction("open", ""))),
            new Tile("Server & Ranks", "minecraft:gold_ingot", true, () -> client.setScreen(new StatsScreen(this, 1)))});
        y = section(y, bh, "Community", new Tile[] {
            new Tile("Social", "minecraft:bell", hasChar, () -> ClientPlayNetworking.send(new Net.SocialAction("open", null))),
            new Tile(war ? "Factions ⚔" : "Factions", "minecraft:shield", hasChar, () -> ClientPlayNetworking.send(new Net.FactionAction("open", ""))),
            new Tile("Regiment", "minecraft:white_banner", hasChar, () -> ClientPlayNetworking.send(new Net.RegimentAction("open", ""))),
            new Tile("Global Market", "minecraft:emerald", hasChar, () -> {
                client.setScreen(new MarketScreen(true));
                ClientPlayNetworking.send(new Net.MarketAction("exchange", "", 0, 0));
            }),
            new Tile("Store", "minecraft:diamond", true, () -> client.setScreen(new StoreScreen(this)))});
        y = section(y, bh, "Home", new Tile[] {
            new Tile("Home", "minecraft:oak_door", hasChar, () -> ClientPlayNetworking.send(new Net.HomeAction("manage", -1, ""))),
            new Tile("Stables", "minecraft:saddle", hasChar, () -> ClientPlayNetworking.send(new Net.StableAction("view", "", "")))});
        if (war) {
            for (var el : children()) {
                if (el instanceof AotButton b && b.getMessage().getString().startsWith("Factions")) b.accent = 0xFFE04A3A;
            }
        }

        y += 4;
        int n = mods ? 3 : 2;
        int sw = (gridW - (n - 1) * GAP) / n;
        add(x, y, sw, bh, Text.literal("Options"), () -> client.setScreen(new OptionsScreen(this, client.options)));
        if (mods) add(x + sw + GAP, y, sw, bh, Text.literal("Mods"), this::openModMenu);
        AotButton leave = add(x + (n - 1) * (sw + GAP), y, gridW - (n - 1) * (sw + GAP), bh,
            Text.literal(client.isInSingleplayer() ? "Save and quit" : "Leave server"), this::leave);
        leave.accent = Ui.RED;
    }

    private record Tile(String label, String icon, boolean active, Runnable action) { }

    private final java.util.List<int[]> headers = new java.util.ArrayList<>();
    private final java.util.List<String> headerNames = new java.util.ArrayList<>();

    /** A labelled block of tiles, three per row. Returns the y below it. */
    private int section(int y, int bh, String name, Tile[] tiles) {
        headers.add(new int[] {gridX, y});
        headerNames.add(name);
        y += 11;
        for (int i = 0; i < tiles.length; i++) {
            Tile t = tiles[i];
            int tx = gridX + (i % 3) * (TW + GAP), ty = y + (i / 3) * (bh + GAP);
            AotButton b = add(tx, ty, TW, bh, Text.literal(t.label()), t.action());
            if (bh >= 18) b.icon(new net.minecraft.item.ItemStack(net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(t.icon()))));
            b.active = t.active();
        }
        return y + ((tiles.length + 2) / 3) * (bh + GAP);
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
        int x = gridX;
        Ui.text(c, Ui.title("ATTACK ON TITAN"), x, 14, 1.8f, Ui.GOLD, false);
        c.drawTextWithShadow(textRenderer, Text.literal("Paused"), x + 2, 34, Ui.MUTED);

        for (int i = 0; i < headers.size(); i++) {
            int[] h = headers.get(i);
            Ui.text(c, Ui.heading(headerNames.get(i).toUpperCase(java.util.Locale.ROOT)), h[0] + 1, h[1] + 1, 0.7f, Ui.GOLD, false);
            c.fill(h[0] + 4 + (int) (Ui.font().getWidth(headerNames.get(i).toUpperCase(java.util.Locale.ROOT)) * 0.7f) + 6, h[1] + 4,
                h[0] + gridW, h[1] + 5, 0x40E0B96A);
        }
        // Character card on the right (when there is room).
        int cw = 190, cx = gridX + gridW + 20, cy = height / 2 - 80;
        if (cx + cw > width - 8) return;
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
