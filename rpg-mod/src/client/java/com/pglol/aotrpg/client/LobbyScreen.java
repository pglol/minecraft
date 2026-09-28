package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.story.CutscenePlayer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * The Extraction lobby, over your character on the balloon's bench (the camera frames you while
 * it's open): the island picked and squad fill across the top, your loadout on the left, the squad
 * and who's ready on the right, and Ready, which becomes the countdown once everyone is.
 * You stay seated while it's up; Walk Around gets you on your feet to use the workbench and forge.
 */
public final class LobbyScreen extends Screen {
    private static Net.LobbyView view;
    private static long viewAt, suppressUntil;
    private final long opened = Util.getMeasuringTimeMs();
    private boolean hudWas;

    public LobbyScreen() {
        super(Text.literal("Lobby"));
    }

    public static void on(Net.LobbyView v) {
        view = v;
        viewAt = Util.getMeasuringTimeMs();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof LobbyScreen s) s.clearAndInit();
    }

    private static boolean aboard(MinecraftClient mc) {
        return mc.player != null && mc.world != null && mc.player.getX() < -399_744
            && mc.world.getRegistryKey().getValue().toString().equals("aot_rpg:homes");
    }

    /** Seated in the balloon with nothing else open: the lobby comes back up. */
    public static void tick(MinecraftClient mc) {
        if (!aboard(mc)) {
            view = null;
            return;
        }
        if (view == null || mc.currentScreen != null || !mc.player.hasVehicle() || Autopilot.locked()) return;
        if (Util.getMeasuringTimeMs() < suppressUntil) return;
        mc.setScreen(new LobbyScreen());
    }

    /** The lobby camera: in front of you on the bench, drifting a little. */
    public static CutscenePlayer.Cam camera(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.currentScreen instanceof LobbyScreen) || mc.player == null || !mc.player.hasVehicle()) return null;
        float t = (Util.getMeasuringTimeMs() % 1_000_000L) / 1000f;
        Vec3d at = mc.player.getLerpedPos(tickDelta).add(0, 0.95, 0);
        double yaw = Math.toRadians(mc.player.getBodyYaw());
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), side = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3d cam = at.add(fwd.multiply(3.3)).add(side.multiply(0.35 * Math.sin(t * 0.25))).add(0, 0.25 + 0.08 * Math.sin(t * 0.4), 0);
        Vec3d look = at.add(side.multiply(0.05 * Math.sin(t * 0.3)));
        Vec3d d = look.subtract(cam);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float cy = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float cp = (float) -(MathHelper.atan2(d.y, h) * MathHelper.DEGREES_PER_RADIAN);
        return new CutscenePlayer.Cam(cam.x, cam.y, cam.z, cy, cp);
    }

    private void act(String a, String arg) {
        ClientPlayNetworking.send(new Net.ExtractionAction(a, arg));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Escape opens the game menu (the lobby returns after). */
    @Override
    public void close() {
        if (client != null) client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));
    }

    @Override
    public void removed() {
        if (client != null) client.options.hudHidden = hudWas;
    }

    private Net.LobbyIsland island() {
        if (view == null) return null;
        for (Net.LobbyIsland i : view.islands()) if (i.id().equals(view.island())) return i;
        return view.islands().isEmpty() ? null : view.islands().get(0);
    }

    private boolean meReady() {
        if (view == null) return false;
        for (Net.LobbyMember m : view.squad()) if (m.you()) return m.ready();
        return false;
    }

    private long countdown() {
        if (view == null || view.countdown() < 0) return -1;
        return Math.max(0, view.countdown() - (Util.getMeasuringTimeMs() - viewAt));
    }

    @Override
    protected void init() {
        if (client != null) {
            hudWas = client.options.hudHidden;
            client.options.hudHidden = true;
        }
        if (view == null) return;
        // The island picker (the leader's call).
        if (view.leader() && view.islands().size() > 1 && view.countdown() < 0) {
            addDrawableChild(new AotButton(width / 2 - 150, 22, 22, 22, Text.literal("<"), () -> cycle(-1)));
            addDrawableChild(new AotButton(width / 2 + 128, 22, 22, 22, Text.literal(">"), () -> cycle(1)));
        }
        AotButton fill = addDrawableChild(new AotButton(width / 2 - 60, 50, 120, 16,
            Text.literal(view.fill() ? "Squad Fill: On" : "Squad Fill: Off"), () -> act("fill", "")));
        fill.selected(view.fill());
        fill.active = view.leader() && view.countdown() < 0;
        fill.textScale = 0.8f;
        // Loadout.
        addDrawableChild(new AotButton(16, height - 88, 160, 20, Ui.heading("Edit Loadout"), () -> act("stash", "")))
            .icon(new ItemStack(Items.ENDER_CHEST));
        addDrawableChild(new AotButton(16, height - 62, 78, 18, Text.literal("Walk Around"), () -> {
            suppressUntil = Util.getMeasuringTimeMs() + 1500;
            act("walk", "");
            if (client != null) client.setScreen(null);
        }));
        addDrawableChild(new AotButton(98, height - 62, 78, 18, Text.literal("Leave"), () -> act("leave", "")));
        // Ready (or, in the countdown, stand down).
        boolean ready = meReady();
        AotButton go = addDrawableChild(new AotButton(width - 176, height - 62, 160, 44,
            Ui.heading(view.countdown() >= 0 ? "" : ready ? "READY" : "READY UP"), () -> act("ready", "")));
        go.selected(ready);
        go.accent = 0xFF5BD35B;
        go.visible = view.countdown() < 0;
        if (view.countdown() >= 0) {
            addDrawableChild(new AotButton(width - 176, height - 16 - 14, 160, 14, Text.literal("Stand down"), () -> act("ready", ""))).textScale = 0.75f;
        }
        // Stash space.
        if (view.rowCost() >= 0) {
            AotButton ex = addDrawableChild(new AotButton(width - 176, 58, 160, 16,
                Text.literal("+9 stash slots · " + String.format(Locale.ROOT, "%,d", view.rowCost())), () -> act("expand", "")));
            ex.textScale = 0.75f;
            ex.active = view.salvage() >= view.rowCost();
        }
    }

    private void cycle(int d) {
        var list = view.islands();
        int i = 0;
        for (int k = 0; k < list.size(); k++) if (list.get(k).id().equals(view.island())) i = k;
        act("island", list.get(Math.floorMod(i + d, list.size())).id());
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        // No dimming: your character is the backdrop. Just shade the edges behind the panels.
        c.fillGradient(0, 0, width, 80, 0xB0000000, 0x00000000);
        c.fillGradient(0, height - 110, width, height, 0x00000000, 0xC0000000);
        for (int x = 0; x < 200; x += 4) {
            int a = (int) (150 * (1 - x / 200f));
            c.fill(x, 70, x + 4, height - 100, a << 24);
            c.fill(width - x - 4, 70, width - x, height - 100, a << 24);
        }
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (view == null) return;
        long t = Util.getMeasuringTimeMs();
        float in = Math.min(1, (t - opened) / 300f);
        // Mode tag and the island.
        Net.LobbyIsland is = island();
        Ui.text(c, Text.literal("EXTRACTION"), 16, 12, 0.8f, 0xFFE03A3A, false);
        if (is != null) {
            int col = is.color();
            int a = (int) (255 * in);
            Ui.text(c, Ui.title(is.name().toUpperCase(Locale.ROOT)), width / 2f, 24, 1.7f, (a << 24) | (col & 0xFFFFFF), true);
            String lv = "Lv " + is.min() + "-" + is.max();
            Ui.text(c, Text.literal(lv), width / 2f, 40, 0.75f, Ui.CREAM, true);
            int lw = 110;
            float pulse = 0.6f + 0.4f * MathHelper.sin(t * 0.004f);
            c.fill(width / 2 - lw, 46, width / 2 + lw, 47, ((int) (160 * pulse) << 24) | (col & 0xFFFFFF));
        }
        // Salvage and stash, top right.
        String sv = String.format(Locale.ROOT, "%,d", view.salvage());
        ItemStack fuel = icon("aot_rpg:gas_refueler");
        Ui.item(c, fuel, width - 32, 8, 1f);
        Ui.text(c, Text.literal(sv), width - 36 - Ui.font().getWidth(sv) * 1.1f, 13, 1.1f, 0xFF9AD0FF, false);
        String st = view.stashUsed() + " / " + view.stashCap();
        Ui.text(c, Text.literal("Stash " + st), width - 16 - Ui.font().getWidth("Stash " + st) * 0.8f, 32, 0.8f, Ui.CREAM, false);
        float frac = view.stashCap() == 0 ? 0 : view.stashUsed() / (float) view.stashCap();
        Ui.bar(c, width - 176, 44, 160, 4, frac, frac > 0.9f ? 0xFFE03A3A : 0xFF6FB6E0);

        drawLoadout(c, t);
        drawSquad(c, t);

        // The countdown, in the Ready button's place.
        long cd = countdown();
        if (cd >= 0) {
            int x = width - 176, y = height - 62;
            int secs = (int) Math.ceil(cd / 1000.0);
            float k = (cd % 1000) / 1000f;
            c.fill(x, y, x + 160, y + 30, 0xE0140404);
            c.drawBorder(x, y, 160, 30, ((int) (120 + 135 * k) << 24) | 0xE03A3A);
            c.fill(x, y + 28, x + (int) (160 * (cd / 10_000f)), y + 30, 0xFFE03A3A);
            Ui.text(c, Ui.heading("DEPLOYING"), x + 10, y + 11, 0.9f, Ui.CREAM, false);
            Ui.text(c, Text.literal(String.valueOf(secs)), x + 140, y + 15 - 8 * (1 + 0.3f * k), 1.6f + 0.3f * k, 0xFFE03A3A, true);
        }
    }

    /** Your loadout: what you'll carry into the run, gear slots only. */
    private void drawLoadout(DrawContext c, long t) {
        if (client == null || client.player == null) return;
        var p = client.player;
        int x = 16, y = 80;
        Ui.text(c, Ui.heading("Loadout"), x, y, 1f, Ui.GOLD, false);
        y += 16;
        String[] names = {"Head", "Chest", "Legs", "Feet"};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) slot(c, x + i * 40, y, names[i], p.getEquippedStack(slots[i]), -1);
        y += 46;
        var inv = p.getInventory();
        ItemStack grip = ItemStack.EMPTY, odm = ItemStack.EMPTY;
        int gas = 0, blades = 0, spears = 0, food = 0;
        ItemStack gasS = ItemStack.EMPTY, bladeS = ItemStack.EMPTY, spearS = ItemStack.EMPTY, foodS = ItemStack.EMPTY;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            String path = Registries.ITEM.getId(s.getItem()).getPath();
            if (path.contains("gas_canister")) { gas += s.getCount(); gasS = s; }
            else if (path.contains("blade_component")) { blades += s.getCount(); bladeS = s; }
            else if (path.contains("thunder_spear")) { spears += s.getCount(); spearS = s; }
            else if (path.contains("odm") && odm.isEmpty()) odm = s;
            else if ((path.contains("grip") || path.contains("blade") || path.contains("apg")) && grip.isEmpty()) grip = s;
            else if (s.getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD)) { food += s.getCount(); foodS = s; }
        }
        slot(c, x, y, "Weapon", grip, -1);
        slot(c, x + 40, y, "ODM", odm, -1);
        slot(c, x + 80, y, "Gas", gasS, gas);
        slot(c, x + 120, y, "Blades", bladeS, blades);
        y += 46;
        slot(c, x, y, "Spears", spearS, spears);
        slot(c, x + 40, y, "Food", foodS, food);
    }

    private void slot(DrawContext c, int x, int y, String label, ItemStack s, int count) {
        boolean has = !s.isEmpty();
        c.fill(x, y, x + 36, y + 36, has ? 0xD0141914 : 0x90101010);
        c.drawBorder(x, y, 36, 36, has ? 0xFF7A6139 : 0xFF34322C);
        if (has) {
            Ui.item(c, s, x + 6, y + 4, 1.5f);
            if (count > 1) Ui.text(c, Text.literal(String.valueOf(count)), x + 34 - Ui.font().getWidth(String.valueOf(count)) * 0.7f, y + 26, 0.7f, Ui.CREAM, false);
        }
        Ui.text(c, Text.literal(label), x + 18, y + 38, 0.6f, has ? Ui.CREAM : Ui.DIM, true);
    }

    /** The squad: who's aboard, their level, ready or not; open seats search when fill is on. */
    private void drawSquad(DrawContext c, long t) {
        int x = width - 176, y = 80, w = 160;
        Ui.text(c, Ui.heading("Squad"), x, y, 1f, Ui.GOLD, false);
        y += 16;
        for (int i = 0; i < 3; i++) {
            Net.LobbyMember m = i < view.squad().size() ? view.squad().get(i) : null;
            c.fill(x, y, x + w, y + 26, m != null && m.you() ? 0xD0302614 : 0xB0101010);
            if (m != null) {
                c.fill(x, y, x + 3, y + 26, m.ready() ? 0xFF5BD35B : 0xFF55524A);
                Ui.text(c, Text.literal((m.leader() ? "★ " : "") + m.name()), x + 9, y + 5, 0.85f, Ui.CREAM, false);
                Ui.text(c, Text.literal("Lv " + m.level()), x + 9, y + 16, 0.6f, Ui.MUTED, false);
                String r = m.ready() ? "READY" : "...";
                Ui.text(c, Text.literal(r), x + w - 8 - Ui.font().getWidth(r) * 0.75f, y + 9, 0.75f, m.ready() ? 0xFF5BD35B : Ui.DIM, false);
            } else if (view.fill()) {
                int dots = (int) ((t / 400) % 4);
                Ui.text(c, Text.literal("Searching" + ".".repeat(dots)), x + 9, y + 9, 0.8f, 0xFF9AD0FF, false);
            } else {
                Ui.text(c, Text.literal("Empty"), x + 9, y + 9, 0.8f, Ui.DIM, false);
            }
            y += 30;
        }
    }

    private static ItemStack icon(String id) {
        var item = Registries.ITEM.get(Identifier.of(id));
        return new ItemStack(item == Items.AIR ? Items.IRON_NUGGET : item);
    }
}
