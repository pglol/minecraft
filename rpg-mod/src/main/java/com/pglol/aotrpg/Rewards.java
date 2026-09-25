package com.pglol.aotrpg;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Reward specs shared by the battle pass and the event shop, written as short strings in their
 * json files so operators can edit them:
 *   marks:250   gold:100   tokens:10   cosmetic:trail_ember   gear:epic
 *   item:minecraft:golden_apple:3   (namespace:path[:count])
 */
public final class Rewards {
    private Rewards() {}

    public static String describe(String spec) {
        String[] a = spec.split(":", 2);
        String v = a.length > 1 ? a[1] : "";
        return switch (a[0]) {
            case "marks" -> v + " Marks";
            case "gold" -> v + " Gold";
            case "tokens" -> v + " " + AotRpg.EVENTS.tokenName();
            case "cosmetic" -> {
                Cosmetics.Def d = Cosmetics.def(v);
                yield (d == null ? v : d.title()) + " " + (d == null ? "cosmetic" : d.slot());
            }
            case "gear" -> rarity(v).title + " gear";
            case "item" -> {
                ItemStack s = item(v);
                yield (s.getCount() > 1 ? s.getCount() + "x " : "") + s.getName().getString();
            }
            default -> spec;
        };
    }

    /** An item id for the reward's icon. */
    public static String icon(String spec) {
        String[] a = spec.split(":", 2);
        String v = a.length > 1 ? a[1] : "";
        return switch (a[0]) {
            case "marks" -> "minecraft:gold_nugget";
            case "gold" -> "minecraft:gold_ingot";
            case "tokens" -> "minecraft:sunflower";
            case "cosmetic" -> "minecraft:amethyst_shard";
            case "gear" -> {
                Item b = AotItems.exact("blade");
                yield b != null ? Registries.ITEM.getId(b).toString() : "minecraft:iron_sword";
            }
            case "item" -> Registries.ITEM.getId(item(v).getItem()).toString();
            default -> "minecraft:paper";
        };
    }

    /** The reward's rarity colour for the UI (0 = none). */
    public static int color(String spec) {
        if (spec.startsWith("gear:")) {
            Integer c = rarity(spec.substring(5)).color.getColorValue();
            return c == null ? 0xFFFFFF : c;
        }
        if (spec.startsWith("cosmetic:")) return 0xC77DFF;
        if (spec.startsWith("gold:")) return 0xFFD54A;
        return 0;
    }

    private static Gear.Rarity rarity(String v) {
        try {
            return Gear.Rarity.valueOf(v.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return Gear.Rarity.COMMON;
        }
    }

    private static ItemStack item(String v) {
        String[] p = v.split(":");
        int count = 1;
        String id = v;
        if (p.length >= 3) {
            id = p[0] + ":" + p[1];
            try {
                count = Integer.parseInt(p[2]);
            } catch (NumberFormatException ignored) { }
        }
        Identifier ident = Identifier.tryParse(id);
        Item it = ident == null ? Items.AIR : Registries.ITEM.get(ident);
        if (it == Items.AIR) return new ItemStack(Items.PAPER);
        return new ItemStack(it, Math.max(1, Math.min(count, it.getMaxCount() * 9)));
    }

    public static void give(ServerPlayerEntity p, String spec, String why) {
        String[] a = spec.split(":", 2);
        String v = a.length > 1 ? a[1] : "";
        long n = 0;
        try {
            n = Long.parseLong(v);
        } catch (NumberFormatException ignored) { }
        switch (a[0]) {
            case "marks" -> AotRpg.WALLET.addMarks(p, n, why);
            case "gold" -> AotRpg.WALLET.addGold(p, n);
            case "tokens" -> AotRpg.EVENTS.addTokens(p, n);
            case "cosmetic" -> AotRpg.COSMETICS.grant(p, v, true);
            case "gear" -> p.getInventory().offerOrDrop(Gear.roll(p.getRandom(), rarity(v), Math.max(1, AotRpg.PROFILES.get(p.getUuid()).level)));
            case "item" -> {
                ItemStack s = item(v);
                while (!s.isEmpty()) {
                    int take = Math.min(s.getCount(), s.getMaxCount());
                    p.getInventory().offerOrDrop(s.copyWithCount(take));
                    s.decrement(take);
                }
            }
            default -> { }
        }
    }
}
