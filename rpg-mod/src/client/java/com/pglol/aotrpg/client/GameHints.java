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

    /**
     * Are these grips out of blades? Danny's grips keep their blade state in the item; this reads the
     * usual signs (a spent durability bar, or a blade/loaded count at zero in the item's data).
     */
    public static boolean bladesSpent(ItemStack s) {
        if (!com.pglol.aotrpg.Loadout.isGrip(s) || com.pglol.aotrpg.AotItems.isApgGun(s)) return false;
        if (s.isDamageable() && s.getDamage() >= s.getMaxDamage() - 1) return true;
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return false;
        return spent(c.copyNbt());
    }

    private static boolean spent(NbtCompound n) {
        for (String k : n.getKeys()) {
            String lk = k.toLowerCase(java.util.Locale.ROOT);
            NbtElement e = n.get(k);
            if (e instanceof NbtCompound inner) {
                if (spent(inner)) return true;
                continue;
            }
            if (!(lk.contains("blade") || lk.contains("loaded") || lk.contains("ammo"))) continue;
            if (e instanceof net.minecraft.nbt.AbstractNbtNumber num && num.doubleValue() <= 0) return true;
        }
        return false;
    }

    private static boolean ready(String id, long restMs) {
        return Util.getMeasuringTimeMs() - lastShown.getOrDefault(id, -restMs - 1) > restMs || current.equals(id);
    }

    /** The one hint that matters most right now, or "" (id|text). */
    private static String[] pick(MinecraftClient mc) {
        var pl = mc.player;
        ItemStack main = pl.getMainHandStack();
        if (bladesSpent(main) || bladesSpent(pl.getOffHandStack())) return new String[] {"reload", "(" + reloadKey() + " to reload blades)"};
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
