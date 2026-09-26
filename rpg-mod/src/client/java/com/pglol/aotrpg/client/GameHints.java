package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small gameplay hints above the hotbar, one at a time: reload your blades when they are spent,
 * how to guard and lock on when you first draw, quick heal when hurt, call your horse, and so on.
 * Each tip shows for a few seconds, then rests a while unless it matters right now (reloading).
 */
public final class GameHints {
    private GameHints() {}

    private static final Map<String, Long> lastShown = new HashMap<>();
    private static String current = "";
    private static long since;

    /** Danny's reload key, found by its name so it shows whatever it is bound to. */
    private static String reloadKey() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (KeyBinding k : mc.options.allKeys) {
            String id = k.getTranslationKey().toLowerCase(java.util.Locale.ROOT);
            if (id.contains("reload")) return k.getBoundKeyLocalizedText().getString().toUpperCase(java.util.Locale.ROOT);
        }
        return "R";
    }

    private static String key(KeyBinding k) {
        return k == null ? "?" : k.getBoundKeyLocalizedText().getString().toUpperCase(java.util.Locale.ROOT);
    }

    private static final java.util.regex.Pattern COUNT = java.util.regex.Pattern.compile("([a-z][a-z ]*?)\\s*:?\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static ItemStack lastStack = ItemStack.EMPTY;
    private static long lastCheck;
    private static int lastState;

    private static List<String> lines(ItemStack s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<String> out = new java.util.ArrayList<>();
        if (mc.player == null || mc.world == null) return out;
        for (Text t : s.getTooltip(net.minecraft.item.Item.TooltipContext.create(mc.world), mc.player,
            net.minecraft.item.tooltip.TooltipType.BASIC)) out.add(t.getString().toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /**
     * What a weapon needs, read from its own tooltip: 1 = blades spent (a loaded grip says "press R
     * to eject blade" and shows its durability), 2 = gun empty (an ammo count at 0), 0 = fine.
     */
    public static int needsReload(ItemStack s) {
        if (s.isEmpty() || !com.pglol.aotrpg.AotItems.isAot(s)) return 0;
        long now = Util.getMeasuringTimeMs();
        if (s == lastStack && now - lastCheck < 250) return lastState;
        lastStack = s;
        lastCheck = now;
        lastState = compute(s);
        return lastState;
    }

    private static int compute(ItemStack s) {
        boolean gun = com.pglol.aotrpg.AotItems.isApgGun(s);
        boolean grip = !gun && com.pglol.aotrpg.Loadout.isGrip(s);
        if (!gun && !grip) return 0;
        List<String> ls = lines(s);
        boolean eject = false, durability = false, durabilityZero = false, ammoZero = false;
        for (String l : ls) {
            if (l.contains("eject")) eject = true;
            if (l.contains("durability")) {
                durability = true;
                java.util.regex.Matcher m = COUNT.matcher(l);
                if (m.find() && Integer.parseInt(m.group(2)) <= 0) durabilityZero = true;
            }
            if (l.contains("thunder")) continue;
            java.util.regex.Matcher m = COUNT.matcher(l);
            while (m.find()) {
                String label = m.group(1);
                if ((label.contains("ammo") || label.contains("cartridge") || label.contains("bullet") || label.contains("shot")
                    || label.contains("round") || label.contains("loaded") || label.contains("charge")) && Integer.parseInt(m.group(2)) <= 0) {
                    ammoZero = true;
                }
            }
            if (gun && l.contains("to reload")) ammoZero = true;
        }
        if (grip) return durabilityZero || (!eject && !durability) ? 1 : 0;
        return ammoZero ? 2 : 0;
    }

    private static boolean ready(String id, long restMs) {
        return Util.getMeasuringTimeMs() - lastShown.getOrDefault(id, -restMs - 1) > restMs || current.equals(id);
    }

    /** Gear you're carrying but not wearing while its slot is empty (the ODM harness, boots, uniform, armor). */
    private static String unworn(net.minecraft.client.network.ClientPlayerEntity pl) {
        for (ItemStack s : pl.getInventory().main) {
            if (s.isEmpty()) continue;
            net.minecraft.entity.EquipmentSlot slot = null;
            String path = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
            if (path.equals("odm_boots")) slot = net.minecraft.entity.EquipmentSlot.FEET;
            else if (path.equals("odm_gear")) slot = net.minecraft.entity.EquipmentSlot.LEGS;
            else if (path.equals("uniform")) slot = net.minecraft.entity.EquipmentSlot.CHEST;
            else if (s.getItem() instanceof net.minecraft.item.ArmorItem a) slot = a.getSlotType();
            if (slot != null && pl.getEquippedStack(slot).isEmpty()) return s.getName().getString();
        }
        return null;
    }

    /** The one hint that matters most right now, or "" (id|text). */
    private static String[] pick(MinecraftClient mc) {
        var pl = mc.player;
        ItemStack main = pl.getMainHandStack();
        int need = needsReload(main);
        if (need == 1) return new String[] {"reload", "(" + reloadKey() + " to reload blades)"};
        if (need == 2) return new String[] {"reload", "(" + reloadKey() + " to reload APG)"};
        String unworn = unworn(pl);
        if (unworn != null && ready("wear", 40_000)) {
            return new String[] {"wear", "Open your inventory (" + key(mc.options.inventoryKey) + ") and put on your " + unworn};
        }
        if (ClientState.stamina >= 0 && ClientState.stamina < ClientState.maxStamina * 0.15f && ready("stamina", 45_000)) {
            return new String[] {"stamina", "Stamina low: land and catch your breath"};
        }
        if (pl.getHealth() < pl.getMaxHealth() * 0.4f && ready("heal", 30_000)) {
            return new String[] {"heal", "(" + key(AotRpgClient.healKey()) + " to quick heal)"};
        }
        if (com.pglol.aotrpg.Loadout.isGrip(main) && ready("guard", 300_000)) {
            return new String[] {"guard", "Hold right click to block · Middle mouse to lock on"};
        }
        if (TitanPlates.near() && ready("nape", 240_000)) {
            return new String[] {"nape", "Aim for the nape: weak blades need several cuts"};
        }
        if (pl.getVehicle() == null && ClientState.hasHorse() && ready("horse", 600_000) && !pl.isCreative()) {
            return new String[] {"horse", "(" + key(AotRpgClient.horseKey()) + " to call your horse)"};
        }
        return null;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null || ClientState.profile == null) return;
        long now = Util.getMeasuringTimeMs();
        String[] h = pick(mc);
        String id = h == null ? "" : h[0];
        if (!id.equals(current)) {
            if (!current.isEmpty()) lastShown.put(current, now);
            current = id;
            since = now;
        }
        if (h == null) return;
        long age = now - since;
        // Tips last 6 seconds; the reload prompt stays while it is true.
        if (!id.equals("reload") && age > 6000) {
            lastShown.put(id, now);
            return;
        }
        float a = Math.min(1, age / 200f);
        Text t = Text.literal(h[1]);
        int w = mc.textRenderer.getWidth(t);
        int x = c.getScaledWindowWidth() / 2, y = c.getScaledWindowHeight() - 78;
        int alpha = (int) (a * 0xB0) << 24;
        c.fill(x - w / 2 - 6, y - 3, x + w / 2 + 6, y + 10, alpha);
        int col = id.equals("reload") ? 0xFFB020 : 0xEDE3C8;
        c.drawTextWithShadow(mc.textRenderer, t, x - w / 2, y, ((int) (a * 255) << 24) | col);
    }
}
