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
    private static int lastSeat = -1;
    private static boolean socialTab;
    private final long opened = Util.getMeasuringTimeMs();
    private boolean bagAsked;
    /** The loadout slot whose picker is open (-1 none). */
    private static int picker = -1;
    private static final String[] SLOT_NAMES = {"Head", "Chest", "Legs", "Feet", "Weapon", "ODM", "Gas", "Blades", "Spears", "Food"};

    public LobbyScreen() {
        super(Text.literal("Lobby"));
    }

    public static void reset() {
        view = null;
        lastSeat = -1;
        suppressUntil = 0;
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
            lastSeat = -1;
            return;
        }
        // Just sat down: face the way the bench faces (into the basket).
        var seat = mc.player.getVehicle();
        if (seat == null) lastSeat = -1;
        else if (seat.getId() != lastSeat) {
            lastSeat = seat.getId();
            float yaw = seat.getYaw();
            mc.player.setYaw(yaw);
            mc.player.setHeadYaw(yaw);
            mc.player.setBodyYaw(yaw);
            mc.player.prevYaw = yaw;
            mc.player.setPitch(0);
        }
        if (view == null || mc.currentScreen != null || !mc.player.hasVehicle() || Autopilot.locked()) return;
        if (Util.getMeasuringTimeMs() < suppressUntil) return;
        mc.setScreen(new LobbyScreen());
    }

    /** On your feet aboard the balloon: the island, the squad and who's ready, in the corner. */
    public static void hud(DrawContext c, net.minecraft.client.render.RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (view == null || !aboard(mc) || mc.currentScreen instanceof LobbyScreen || CutscenePlayer.active() || mc.options.hudHidden) return;
        int w = c.getScaledWindowWidth(), x = w - 150, y = 40;
        Net.LobbyIsland is = null;
        for (Net.LobbyIsland i : view.islands()) if (i.id().equals(view.island())) is = i;
        c.fill(x - 6, y - 6, w - 8, y + 30 + view.squad().size() * 12 + 14, 0xA0000000);
        c.fill(x - 6, y - 6, x - 4, y + 30 + view.squad().size() * 12 + 14, is == null ? Ui.GOLD : is.color());
        Ui.text(c, Text.literal("EXTRACTION"), x, y, 0.6f, 0xFFE03A3A, false);
        if (is != null) Ui.text(c, Ui.heading(is.name()), x, y + 9, 0.9f, is.color(), false);
        Ui.text(c, Text.literal(view.fill() ? "Squad Fill On" : "Squad Fill Off"), x, y + 20, 0.6f, Ui.CREAM, false);
        int yy = y + 32;
        for (Net.LobbyMember m : view.squad()) {
            c.fill(x, yy + 2, x + 4, yy + 6, m.ready() ? 0xFF5BD35B : 0xFF55524A);
            Ui.text(c, Text.literal(m.name()), x + 8, yy, 0.7f, m.you() ? Ui.GOLD : Ui.CREAM, false);
            yy += 12;
        }
        long cd = view.countdown() < 0 ? -1 : Math.max(0, view.countdown() - (Util.getMeasuringTimeMs() - viewAt));
        if (cd >= 0) Ui.text(c, Ui.heading("Deploying " + (int) Math.ceil(cd / 1000.0)), x, yy + 2, 0.85f, 0xFFE03A3A, false);
        else Ui.text(c, Text.literal("Take a seat to ready up"), x, yy + 2, 0.6f, Ui.MUTED, false);
    }

    /** The lobby camera: in front of you on the bench, drifting a little. */
    public static CutscenePlayer.Cam camera(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.currentScreen instanceof LobbyScreen) || mc.player == null || !mc.player.hasVehicle()) return null;
        float t = (Util.getMeasuringTimeMs() % 1_000_000L) / 1000f;
        Vec3d at = mc.player.getLerpedPos(tickDelta).add(0, 0.95, 0);
        var seat = mc.player.getVehicle();
        double yaw = Math.toRadians(seat != null ? seat.getYaw() : mc.player.getBodyYaw());
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

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (!socialTab && view != null) {
            for (int k = 0; k < 10; k++) {
                int[] at = slotPos(k);
                if (mx >= at[0] && mx < at[0] + 36 && my >= at[1] && my < at[1] + 36) {
                    picker = picker == k ? -1 : k;
                    if (client != null && client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), 0.5f, 1.2f);
                    clearAndInit();
                    return true;
                }
            }
        }
        if (picker >= 0) {
            picker = -1;
            clearAndInit();
            return true;
        }
        return false;
    }

    /** Escape opens the game menu (the lobby returns after). */
    @Override
    public void close() {
        if (client != null) client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));
    }

    @Override
    public void removed() {
        HudHide.show("lobby");
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
        HudHide.hide("lobby");
        if (!bagAsked) {
            bagAsked = true;
            act("bag", "");
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
        // The left panel: your loadout, or squad up (friends and recent teammates).
        addDrawableChild(new AotButton(16, 64, 78, 16, Ui.heading("Loadout"), () -> {
            socialTab = false;
            clearAndInit();
        })).selected(!socialTab);
        addDrawableChild(new AotButton(98, 64, 78, 16, Ui.heading("Squad Up"), () -> {
            socialTab = true;
            clearAndInit();
        })).selected(socialTab);
        if (socialTab) {
            int y = 88;
            for (Net.LobbyFriend f : view.social()) {
                if (y > height - 120) break;
                if (!f.where().equals("aboard") && !f.where().equals("offline") && !f.where().equals("in a run")) {
                    AotButton inv = addDrawableChild(new AotButton(126, y + 3, 50, 16, Text.literal("Invite"), () -> act("invite", f.id().toString())));
                    inv.textScale = 0.75f;
                    inv.accent = 0xFF5BD35B;
                }
                if (!f.friend()) {
                    AotButton add = addDrawableChild(new AotButton(104, y + 3, 18, 16, Text.literal("+"), () -> act("friend", f.id().toString())));
                    add.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Add friend")));
                }
                y += 24;
            }
        }
        pickerButtons();
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

        if (socialTab) drawSocial(c);
        else drawLoadout(c, t);
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

    /** Squad up: friends and recent teammates, where they are, invite or befriend. */
    private void drawSocial(DrawContext c) {
        int y = 88;
        if (view.social().isEmpty()) Ui.text(c, Text.literal("No friends or teammates yet"), 16, y + 4, 0.75f, Ui.DIM, false);
        for (Net.LobbyFriend f : view.social()) {
            if (y > height - 120) break;
            c.fill(16, y, 176, y + 22, 0xB0101010);
            int dot = switch (f.where()) {
                case "aboard" -> 0xFFE0B96A;
                case "online" -> 0xFF5BD35B;
                case "in a run" -> 0xFFE0823A;
                default -> 0xFF55524A;
            };
            c.fill(20, y + 8, 25, y + 13, dot);
            Ui.text(c, Text.literal(f.name()), 30, y + 4, 0.75f, f.where().equals("offline") ? Ui.DIM : Ui.CREAM, false);
            String tag = f.where() + (f.friend() ? "" : f.recent() ? "  \u00B7  teammate" : "");
            Ui.text(c, Text.literal(tag), 30, y + 13, 0.55f, dot, false);
            y += 24;
        }
    }

    /** Where loadout slot k sits on screen. */
    private static int[] slotPos(int k) {
        if (k < 4) return new int[] {16 + k * 40, 88};
        if (k < 8) return new int[] {16 + (k - 4) * 40, 134};
        return new int[] {16 + (k - 8) * 40, 180};
    }

    /** Does this stack go in loadout slot k? */
    private boolean fits(int k, ItemStack s) {
        if (s.isEmpty() || client == null || client.player == null) return false;
        String path = Registries.ITEM.getId(s.getItem()).getPath();
        return switch (k) {
            case 0 -> client.player.getPreferredEquipmentSlot(s) == EquipmentSlot.HEAD;
            case 1 -> client.player.getPreferredEquipmentSlot(s) == EquipmentSlot.CHEST;
            case 2 -> client.player.getPreferredEquipmentSlot(s) == EquipmentSlot.LEGS;
            case 3 -> client.player.getPreferredEquipmentSlot(s) == EquipmentSlot.FEET;
            case 4 -> !path.contains("odm") && !path.contains("gas") && !path.contains("component") && !path.contains("thunder_spear")
                && (path.contains("grip") || path.contains("blade") || path.contains("apg") || com.pglol.aotrpg.Loadout.isMelee(s)
                    || com.pglol.aotrpg.Loadout.isRanged(s));
            case 5 -> path.contains("odm");
            case 6 -> path.contains("gas_canister");
            case 7 -> path.contains("blade_component");
            case 8 -> path.contains("thunder_spear");
            default -> s.getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD);
        };
    }

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** What's in loadout slot k now: the stack, how many of it you carry, and its inventory index (-1 worn). */
    private Object[] current(int k) {
        var p = client.player;
        if (k < 4) return new Object[] {p.getEquippedStack(ARMOR[k]), 1, -1};
        var inv = p.getInventory();
        ItemStack first = ItemStack.EMPTY;
        int count = 0, index = -1;
        for (int i = 0; i < inv.main.size(); i++) {
            ItemStack s = inv.main.get(i);
            if (!fits(k, s)) continue;
            if (first.isEmpty()) {
                first = s;
                index = i;
            }
            count += s.getCount();
        }
        return new Object[] {first, count, index};
    }

    /** Your loadout: what you'll carry into the run, gear slots only. Click one to swap or take it off. */
    private void drawLoadout(DrawContext c, long t) {
        if (client == null || client.player == null) return;
        for (int k = 0; k < 10; k++) {
            int[] at = slotPos(k);
            Object[] cur = current(k);
            slot(c, at[0], at[1], SLOT_NAMES[k], (ItemStack) cur[0], k >= 6 ? (int) cur[1] : -1);
            if (k == picker) c.drawBorder(at[0] - 1, at[1] - 1, 38, 38, 0xFFF2C14E);
        }
        if (picker >= 0) {
            // The picker: a small card beside the loadout.
            int[] at = slotPos(picker);
            int px = 184, py = Math.min(at[1], height - 150), rows = pickerItems().size();
            int ph = 30 + Math.max(1, Math.min(8, rows)) * 20 + 24;
            c.fill(px, py, px + 180, py + ph, 0xF0100C0A);
            c.drawBorder(px, py, 180, ph, 0xFF7A6139);
            c.fill(px, py, px + 180, py + 2, 0xFFF2C14E);
            Ui.text(c, Ui.heading(SLOT_NAMES[picker]), px + 8, py + 8, 0.85f, Ui.GOLD, false);
            if (rows == 0) Ui.text(c, Text.literal("Nothing in your satchel fits"), px + 8, py + 30, 0.7f, Ui.DIM, false);
        }
    }

    /** Satchel slots holding something for the open picker's slot. */
    private java.util.List<Integer> pickerItems() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (picker < 0) return out;
        for (var e : ClientState.bag.entrySet()) if (fits(picker, e.getValue())) out.add(e.getKey());
        out.sort((a, b) -> BagScreen.quality(ClientState.bag.get(b)) - BagScreen.quality(ClientState.bag.get(a)));
        return out;
    }

    /** Called when the satchel changes: the picker's list follows. */
    public void refreshBag() {
        clearAndInit();
    }

    private void pickerButtons() {
        if (picker < 0 || socialTab || client == null || client.player == null) return;
        int[] at = slotPos(picker);
        int px = 184, py = Math.min(at[1], height - 150);
        int y = py + 24;
        int k = picker;
        for (int slot : pickerItems()) {
            if (y > py + 24 + 7 * 20) break;
            ItemStack s = ClientState.bag.get(slot);
            String name = s.getName().getString();
            if (name.length() > 22) name = name.substring(0, 21) + "\u2026";
            AotButton b = addDrawableChild(new AotButton(px + 6, y, 168, 18,
                Text.literal(name + (s.getCount() > 1 ? " x" + s.getCount() : "")), () -> {
                    ClientPlayNetworking.send(new Net.BagAction("equip", slot, k < 4 ? 103 - k : -1));
                    picker = -1;
                }));
            b.icon(s);
            b.textScale = 0.7f;
            b.accent = Ui.rarityTone(BagScreen.quality(s));
            y += 20;
        }
        Object[] cur = current(k);
        ItemStack now = (ItemStack) cur[0];
        int index = (int) cur[2];
        int ph = 30 + Math.max(1, Math.min(8, pickerItems().size())) * 20 + 24;
        AotButton off = addDrawableChild(new AotButton(px + 6, py + ph - 22, 80, 16, Text.literal("Take off"), () -> {
            ClientPlayNetworking.send(new Net.BagAction("store", -1, k < 4 ? 103 - k : index));
            picker = -1;
        }));
        off.textScale = 0.7f;
        off.accent = Ui.RED;
        off.active = !now.isEmpty() && (k < 4 || index >= 0 && index < 9);
        AotButton close = addDrawableChild(new AotButton(px + 94, py + ph - 22, 80, 16, Text.literal("Close"), () -> {
            picker = -1;
            clearAndInit();
        }));
        close.textScale = 0.7f;
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
            } else if (view.fill() && (meReady() || view.countdown() >= 0)) {
                int dots = (int) ((t / 400) % 4);
                Ui.text(c, Text.literal("Searching" + ".".repeat(dots)), x + 9, y + 9, 0.8f, 0xFF9AD0FF, false);
            } else {
                Ui.text(c, Text.literal("Open"), x + 9, y + 9, 0.8f, Ui.DIM, false);
            }
            y += 30;
        }
    }

    private static ItemStack icon(String id) {
        var item = Registries.ITEM.get(Identifier.of(id));
        return new ItemStack(item == Items.AIR ? Items.IRON_NUGGET : item);
    }
}
