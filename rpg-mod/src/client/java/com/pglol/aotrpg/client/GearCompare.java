package com.pglol.aotrpg.client;

import com.pglol.aotrpg.BladeCare;
import com.pglol.aotrpg.Gear;
import com.pglol.aotrpg.Infusions;
import com.pglol.aotrpg.Loadout;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Side by side: a piece you're about to equip against what's in that place now, stat by stat,
 * green where it's better and red where it's worse.
 */
public final class GearCompare {
    private GearCompare() {}

    /** Stat -> value. Keys ending in " %" are percentages. */
    static Map<String, Double> stats(ItemStack s) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        if (Gear.isGear(s)) {
            m.put("Item level", (double) Gear.requiredLevel(s));
            if (Gear.power(s) != 0) m.put("Power %", Gear.power(s) * 100);
        }
        var mods = s.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (mods != null) {
            for (var e : mods.modifiers()) {
                String name = Text.translatable(e.attribute().value().getTranslationKey()).getString();
                boolean pct = e.modifier().operation() != EntityAttributeModifier.Operation.ADD_VALUE;
                String key = pct ? name + " %" : name;
                m.merge(key, e.modifier().value() * (pct ? 100 : 1), Double::sum);
            }
        }
        if (Loadout.isGrip(s) && Gear.isGear(s)) m.put("Blade life x", Math.round(10 / (1 - BladeCare.temper(s))) / 10.0);
        if (Gear.isGear(s) && Gear.data(s).getDouble("secondwind") > 0) m.put("Second Wind %", Gear.data(s).getDouble("secondwind") * 100);
        return m;
    }

    /** Lower is better for these. */
    private static boolean lowerBetter(String k) {
        return k.contains("Fall Damage") || k.contains("Item level");
    }

    /** +1 better overall, -1 worse, 0 about even (for the badge on each place). */
    public static int verdict(ItemStack incoming, ItemStack current) {
        if (current == null || current.isEmpty()) return 1;
        if (!Gear.isGear(incoming) && !Gear.isGear(current)) return 0;
        Map<String, Double> a = stats(incoming), b = stats(current);
        double score = 0;
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            if (k.equals("Item level")) continue;
            double x = a.getOrDefault(k, 0.0), y = b.getOrDefault(k, 0.0);
            double scale = Math.max(Math.abs(x), Math.abs(y));
            if (scale < 1e-6) continue;
            double d = (x - y) / scale;
            score += lowerBetter(k) ? -d : d;
        }
        score += 0.6 * (Gear.rarityOf(incoming) - Gear.rarityOf(current));
        if (Infusions.of(incoming) != null && Infusions.of(current) == null) score += 0.5;
        if (Infusions.of(current) != null && Infusions.of(incoming) == null) score -= 0.5;
        return score > 0.15 ? 1 : score < -0.15 ? -1 : 0;
    }

    /** The comparison card, drawn near (mx, my). */
    public static void render(DrawContext c, ItemStack incoming, ItemStack current, String place, int mx, int my, int sw, int sh) {
        Map<String, Double> a = stats(incoming), b = stats(current);
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        List<Object[]> rows = new ArrayList<>();
        for (String k : keys) rows.add(new Object[] {k, a.getOrDefault(k, 0.0), b.getOrDefault(k, 0.0)});
        List<String> extras = new ArrayList<>();
        extra(extras, incoming, current);
        int w = 230, h = 40 + rows.size() * 11 + extras.size() * 11 + (current.isEmpty() ? 0 : 4);
        int x = Math.min(mx + 12, sw - w - 6), y = Math.max(6, Math.min(my - h - 8, sh - h - 6));
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 400);
        c.fill(x, y, x + w, y + h, 0xF0120E0A);
        c.drawBorder(x, y, w, h, 0xFF7A6139);
        int v = verdict(incoming, current);
        String head = current.isEmpty() ? place + " is empty" : "vs " + place + ": " + current.getName().getString();
        while (head.length() > 6 && Ui.font().getWidth(head) * 0.75f > w - 12) head = head.substring(0, head.length() - 2);
        Ui.text(c, Text.literal(head), x + 6, y + 6, 0.75f, Ui.GOLD, false);
        String vt = v > 0 ? "▲ Upgrade" : v < 0 ? "▼ Downgrade" : "≈ Sidegrade";
        Ui.text(c, Ui.heading(vt), x + 6, y + 18, 0.85f, v > 0 ? 0xFF5BD35B : v < 0 ? 0xFFE0463A : Ui.CREAM, false);
        int ry = y + 32;
        Ui.text(c, Text.literal("new"), x + w - 96, ry - 2, 0.55f, Ui.CREAM, true);
        Ui.text(c, Text.literal("now"), x + w - 58, ry - 2, 0.55f, Ui.CREAM, true);
        ry += 6;
        for (Object[] r : rows) {
            String k = (String) r[0];
            double nv = (double) r[1], cv = (double) r[2], d = nv - cv;
            boolean pct = k.endsWith(" %");
            String label = pct ? k.substring(0, k.length() - 2) : k.endsWith(" x") ? k.substring(0, k.length() - 2) : k;
            Ui.text(c, Text.literal(label), x + 6, ry, 0.62f, Ui.CREAM, false);
            Ui.text(c, Text.literal(fmt(nv, k)), x + w - 96, ry, 0.62f, 0xFFFFFFFF, true);
            Ui.text(c, Text.literal(current.isEmpty() ? "-" : fmt(cv, k)), x + w - 58, ry, 0.62f, 0xFFBFB6A0, true);
            if (Math.abs(d) > 1e-6 && !current.isEmpty()) {
                boolean good = lowerBetter(k) ? d < 0 : d > 0;
                if (k.equals("Item level")) good = true;
                String ds = (d > 0 ? "+" : "") + fmt(d, k);
                Ui.text(c, Text.literal(ds), x + w - 6 - Ui.font().getWidth(ds) * 0.62f, ry, 0.62f,
                    k.equals("Item level") ? Ui.CREAM : good ? 0xFF5BD35B : 0xFFE0463A, false);
            }
            ry += 11;
        }
        for (String e : extras) {
            Ui.text(c, Text.literal(e.substring(1)), x + 6, ry, 0.62f, e.charAt(0) == '+' ? 0xFF5BD35B : e.charAt(0) == '-' ? 0xFFE0463A : Ui.CREAM, false);
            ry += 11;
        }
        c.getMatrices().pop();
    }

    private static void extra(List<String> out, ItemStack a, ItemStack b) {
        var ia = Infusions.of(a);
        var ib = Infusions.of(b);
        if (ia != ib) {
            if (ia != null) out.add("+Gains " + ia.title + ": " + ia.effect.toLowerCase(java.util.Locale.ROOT));
            if (ib != null) out.add("-Loses " + ib.title);
        }
        boolean ta = Gear.isGear(a) && Gear.data(a).getBoolean("twin"), tb = Gear.isGear(b) && Gear.data(b).getBoolean("twin");
        if (ta && !tb) out.add("+Gains Twin Cut");
        if (tb && !ta) out.add("-Loses Twin Cut");
    }

    private static String fmt(double v, String k) {
        if (k.equals("Item level")) return String.valueOf((int) Math.round(v));
        if (k.endsWith(" x")) return (Math.abs(v - Math.rint(v)) < 0.05 ? String.valueOf((int) Math.rint(v)) : String.format(java.util.Locale.ROOT, "%.1f", v)) + "x";
        String n = Math.abs(v) >= 10 ? String.format(java.util.Locale.ROOT, "%.1f", v) : String.format(java.util.Locale.ROOT, "%.2f", v);
        return k.endsWith(" %") ? n + "%" : n;
    }
}
