package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.Map;

/** Your estate: construction on your land, the farm's produce, and your pets. */
public final class EstateScreen extends Screen {
    private static final String[] TABS = {"Construction", "Farm & Pen", "Pets"};
    private static final Map<String, Item> ICONS = Map.of("FORT1", Items.SPRUCE_LOG, "FORT2", Items.STONE_BRICKS, "FORT3", Items.BELL,
        "FARM", Items.WHEAT, "PEN", Items.OAK_FENCE);
    private static final Map<String, Item> PET_ICONS = Map.of("rabbit", Items.RABBIT_SPAWN_EGG, "frog", Items.FROG_SPAWN_EGG, "cat", Items.CAT_SPAWN_EGG,
        "hound", Items.WOLF_SPAWN_EGG, "bee", Items.BEE_SPAWN_EGG, "parrot", Items.PARROT_SPAWN_EGG, "fox", Items.FOX_SPAWN_EGG,
        "panda", Items.PANDA_SPAWN_EGG, "allay", Items.ALLAY_SPAWN_EGG);
    private static int tab;
    private int left, top, w, h, ticks;

    public EstateScreen() {
        super(Text.literal("Estate"));
    }

    public EstateScreen(int tab) {
        this();
        EstateScreen.tab = tab;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private static void act(String a, String arg) {
        ClientPlayNetworking.send(new Net.EstateAction(a, arg));
    }

    @Override
    public void tick() {
        // Watch construction and produce tick along.
        if (++ticks % 40 == 0) act("view", "");
    }

    @Override
    protected void init() {
        w = Math.min(540, width - 20);
        h = Math.min(310, height - 60);
        left = (width - w) / 2;
        top = Math.max(46, (height - h) / 2 + 10);
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            AotButton b = addDrawableChild(new AotButton(left + i * 114, top - 22, 110, 20, Ui.heading(TABS[i]), () -> {
                tab = t;
                refresh();
            }));
            b.selected = tab == i;
        }
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        Net.EstateView v = ClientState.estate;
        if (v == null) return;
        if (tab == 0 && v.hasPlot()) {
            int y = top + 40;
            for (Net.EstateProject p : v.projects()) {
                if (p.state() == 1) {
                    AotButton b = addDrawableChild(new AotButton(left + w - 130, y + 8, 118, 20,
                        Text.literal("Build · " + String.format(Locale.ROOT, "%,d", p.price())), () -> act("build", p.id())));
                    b.accent = Ui.GOLD;
                }
                y += 48;
            }
        }
        if (tab == 1 && v.hasPlot()) {
            AotButton b = addDrawableChild(new AotButton(left + w / 2 - 80, top + h - 40, 160, 24, Ui.heading("Collect harvest"), () -> act("harvest", "")));
            b.active = v.harvest() > 0;
            b.accent = 0xFF5BD35B;
        }
        if (tab == 2) {
            int cols = 3, cw = (w - 20 - (cols - 1) * 8) / cols, ch = 74;
            for (int i = 0; i < v.pets().size(); i++) {
                Net.EstatePet p = v.pets().get(i);
                int x = left + 10 + (i % cols) * (cw + 8), y = top + 34 + (i / cols) * (ch + 6);
                if (!p.owned()) {
                    addDrawableChild(new AotButton(x + 6, y + ch - 24, cw - 12, 18, Text.literal("Adopt · " + String.format(Locale.ROOT, "%,d", p.price())),
                        () -> act("buypet", p.id()))).accent = Ui.GOLD;
                } else {
                    AotButton b = addDrawableChild(new AotButton(x + 6, y + ch - 24, cw - 12, 18,
                        Text.literal(p.companion() ? "Leave at home" : "Bring along"), () -> act("companion", p.id())));
                    b.selected = p.companion();
                    b.active = v.atHome() || p.companion();
                }
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("ESTATE"), width / 2f, top - 42, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.EstateView v = ClientState.estate;
        if (v == null) return;
        if (tab < 2 && !v.hasPlot()) {
            Ui.text(c, Text.literal("Buy a property plot to build on it (Home → property)."), width / 2f, top + h / 2f, 0.9f, Ui.MUTED, true);
            return;
        }
        switch (tab) {
            case 0 -> drawBuild(c, v);
            case 1 -> drawProduce(c, v);
            default -> drawPets(c, v);
        }
    }

    private void drawBuild(DrawContext c, Net.EstateView v) {
        Ui.text(c, Ui.heading(v.plot()), left + 10, top + 8, 1f, Ui.GOLD, false);
        String[] tiers = {"No walls", "Palisade", "Stone walls", "Fortress"};
        Ui.text(c, Text.literal("Defenses: " + tiers[Math.min(3, v.fort())] + "  ·  builders work while your land is loaded; watch them build it"),
            left + 10, top + 21, 0.62f, Ui.MUTED, false);
        int y = top + 40;
        for (Net.EstateProject p : v.projects()) {
            boolean done = p.state() == 3, building = p.state() == 2, locked = p.state() == 0;
            c.fill(left + 8, y, left + w - 8, y + 44, done ? 0x3020A040 : building ? 0x40E0B96A : 0x28000000);
            c.drawBorder(left + 8, y, w - 16, 44, done ? 0xFF5BD35B : building ? Ui.GOLD : 0x607A6139);
            var m = c.getMatrices();
            m.push();
            m.translate(left + 14, y + 8, 0);
            m.scale(1.7f, 1.7f, 1);
            c.drawItem(new ItemStack(ICONS.getOrDefault(p.id(), Items.BRICKS)), 0, 0);
            m.pop();
            Ui.text(c, Ui.heading(p.title()), left + 48, y + 5, 0.95f, locked ? Ui.DIM : Ui.CREAM, false);
            Ui.text(c, Text.literal(textRenderer.trimToWidth(p.desc(), (int) ((w - 200) / 0.62f))), left + 48, y + 18, 0.62f, Ui.MUTED, false);
            if (building) {
                int bw = w - 200;
                Ui.bar(c, left + 48, y + 31, bw, 6, p.progress(), Ui.GOLD);
                Ui.text(c, Text.literal("Building · " + Math.round(p.progress() * 100) + "%"), left + 48 + bw + 8, y + 29, 0.7f, Ui.GOLD, false);
            } else if (done) {
                Ui.text(c, Ui.heading("✔ Built"), left + w - 70, y + 16, 0.9f, 0xFF5BD35B, false);
            } else if (locked) {
                Ui.text(c, Text.literal(v.projects().stream().anyMatch(x -> x.state() == 2) ? "Builders busy" : "Needs the tier before"),
                    left + w - 128, y + 16, 0.7f, Ui.DIM, false);
            }
            y += 48;
        }
    }

    private void drawProduce(DrawContext c, Net.EstateView v) {
        boolean farm = false, pen = false;
        for (Net.EstateProject p : v.projects()) {
            if (p.id().equals("FARM") && p.state() == 3) farm = true;
            if (p.id().equals("PEN") && p.state() == 3) pen = true;
        }
        Ui.text(c, Ui.heading("Your land feeds you"), left + 10, top + 8, 1f, Ui.GOLD, false);
        if (!farm && !pen) {
            Ui.text(c, Text.literal("Build a Crop Field or an Animal Pen (Construction tab) and they'll bring in food."), left + 10, top + 30, 0.75f, Ui.MUTED, false);
            return;
        }
        int y = top + 34;
        if (farm) {
            Ui.text(c, Text.literal("Crop Field: bread, carrots, potatoes and wheat"), left + 14, y, 0.8f, Ui.CREAM, false);
            y += 14;
        }
        if (pen) {
            Ui.text(c, Text.literal("Animal Pen: beef, eggs, wool, leather and milk"), left + 14, y, 0.8f, Ui.CREAM, false);
            y += 14;
        }
        y += 10;
        Ui.text(c, Ui.title(v.harvest() + " / 4"), width / 2f, y + 10, 2f, v.harvest() > 0 ? 0xFF5BD35B : Ui.MUTED, true);
        Ui.text(c, Text.literal("batches ready (one every 30 minutes, up to 4)"), width / 2f, y + 34, 0.7f, Ui.MUTED, true);
        if (v.nextHarvest() >= 0 && v.harvest() < 4) {
            long s = v.nextHarvest();
            Ui.text(c, Text.literal(String.format(Locale.ROOT, "Next batch in %d:%02d", s / 60, s % 60)), width / 2f, y + 46, 0.7f, Ui.CREAM, true);
        }
        Ui.text(c, Text.literal("Collect at your property: it goes to your satchel."), width / 2f, top + h - 54, 0.62f, Ui.DIM, true);
    }

    private void drawPets(DrawContext c, Net.EstateView v) {
        Ui.text(c, Ui.heading("Pets"), left + 10, top + 8, 1f, Ui.GOLD, false);
        Ui.text(c, Text.literal(v.atHome() ? "Your pets wait at home. Pick one to bring along on your adventures."
            : "Change your companion at home. Your pets wait there for you."), left + 10, top + 20, 0.62f, Ui.MUTED, false);
        int cols = 3, cw = (w - 20 - (cols - 1) * 8) / cols, ch = 74;
        for (int i = 0; i < v.pets().size(); i++) {
            Net.EstatePet p = v.pets().get(i);
            int x = left + 10 + (i % cols) * (cw + 8), y = top + 34 + (i / cols) * (ch + 6);
            c.fill(x, y, x + cw, y + ch, p.companion() ? 0x40E0B96A : p.owned() ? 0x2830A050 : 0x28000000);
            c.drawBorder(x, y, cw, ch, p.companion() ? Ui.GOLD : p.owned() ? 0xFF5BD35B : 0x607A6139);
            var m = c.getMatrices();
            m.push();
            m.translate(x + 6, y + 6, 0);
            m.scale(1.6f, 1.6f, 1);
            c.drawItem(new ItemStack(PET_ICONS.getOrDefault(p.id(), Items.EGG)), 0, 0);
            m.pop();
            Ui.text(c, Ui.heading(p.name()), x + 36, y + 7, 0.85f, Ui.CREAM, false);
            Ui.text(c, Text.literal(p.companion() ? "With you" : p.owned() ? "At home" : "For adoption"), x + 36, y + 19, 0.6f,
                p.companion() ? Ui.GOLD : p.owned() ? 0xFF5BD35B : Ui.MUTED, false);
            Ui.wrapped(c, Text.literal(p.desc()), x + 6, y + 34, cw - 12, Ui.MUTED);
        }
    }
}
