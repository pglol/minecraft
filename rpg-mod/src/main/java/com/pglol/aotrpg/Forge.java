package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The forge (a home upgrade; the anvil opens it). Upgrade gear +1..+10 with Marks and metal, or
 * forge new gear from materials. The strike minigame's quality and your smithing level raise
 * the chance of success and the rarity of what you forge.
 */
public final class Forge {
    public record Recipe(String id, String title, Map<String, Integer> materials, long marks, String base) { }

    public static final List<Recipe> RECIPES = List.of(
        new Recipe("blade", "ODM Blade", mats("minecraft:iron_ingot", 4, "dannys-aot:ultrahard_steel_ingot", 1), 200, "dannys-aot:blade"),
        new Recipe("apg_gun", "APG Gun", mats("minecraft:iron_ingot", 6, "dannys-aot:ultrahard_steel_ingot", 2, "minecraft:gold_ingot", 2), 400, "dannys-aot:apg_gun"),
        new Recipe("sword", "Sword", mats("minecraft:iron_ingot", 5), 120, "minecraft:iron_sword"),
        new Recipe("armor", "Armor piece", mats("minecraft:iron_ingot", 8, "dannys-aot:ultrahard_leather", 1), 250, ""),
        new Recipe("components", "Blade components x8", mats("minecraft:iron_ingot", 2), 20, "dannys-aot:blade_component"));

    private static Map<String, Integer> mats(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static Item item(String id) {
        var i = net.minecraft.util.Identifier.tryParse(id);
        return i == null ? Items.AIR : net.minecraft.registry.Registries.ITEM.get(i);
    }

    /** Upgrade cost for gear at level up: marks, iron, ultrahard steel. */
    public static long[] cost(ItemStack s) {
        var d = Gear.data(s);
        int up = d.getInt("up");
        int r = 0;
        try {
            r = Gear.Rarity.valueOf(d.getString("rarity")).ordinal();
        } catch (Exception ignored) { }
        long marks = Math.round(40 * Math.pow(up + 1, 1.5) * (1 + 0.5 * r));
        return new long[] {marks, 2 + up, Math.max(0, up - 4)};
    }

    public static double chance(ItemStack s, int smithing, double quality) {
        int up = Gear.data(s).getInt("up");
        return Math.max(0.15, Math.min(0.98, 1.0 - up * 0.075 + smithing * 0.004 + quality * 0.2));
    }

    private static int count(ServerPlayerEntity p, Item it) {
        int n = 0;
        for (ItemStack s : p.getInventory().main) if (s.isOf(it) && !Gear.isGear(s)) n += s.getCount();
        return n;
    }

    private static void take(ServerPlayerEntity p, Item it, int n) {
        for (ItemStack s : p.getInventory().main) {
            if (n <= 0) return;
            if (!s.isOf(it) || Gear.isGear(s)) continue;
            int k = Math.min(n, s.getCount());
            s.decrement(k);
            n -= k;
        }
    }

    public void open(ServerPlayerEntity p) {
        send(p, true);
    }

    public void upgrade(ServerPlayerEntity p, int slot, double quality) {
        if (slot < 0 || slot >= p.getInventory().main.size()) return;
        ItemStack s = p.getInventory().main.get(slot);
        if (!Gear.isGear(s) || Gear.data(s).getInt("up") >= 10) return;
        long[] c = cost(s);
        Item iron = Items.IRON_INGOT, steel = item("dannys-aot:ultrahard_steel_ingot");
        if (count(p, iron) < c[1] || (c[2] > 0 && count(p, steel) < c[2])) {
            p.sendMessage(Text.literal("Not enough metal.").formatted(Formatting.RED), true);
            return;
        }
        if (!AotRpg.WALLET.spendMarks(p, c[0])) {
            p.sendMessage(Text.literal("You need " + c[0] + " Marks.").formatted(Formatting.RED), true);
            return;
        }
        take(p, iron, (int) c[1]);
        if (c[2] > 0) take(p, steel, (int) c[2]);
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        quality = Math.max(0, Math.min(1, quality));
        double ch = chance(s, Lifestyle.level(pr, Lifestyle.SMITHING), quality);
        if (p.getRandom().nextDouble() < ch) {
            int lvl = Gear.upgrade(s);
            p.sendMessage(Text.literal("Upgraded to +" + lvl + "!").formatted(Formatting.GOLD, Formatting.BOLD), true);
            p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.8f, 1.2f);
        } else {
            p.sendMessage(Text.literal("The metal cracked. The gear is unharmed, the materials are spent.").formatted(Formatting.RED), true);
            p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_DESTROY, SoundCategory.BLOCKS, 0.6f, 1.1f);
        }
        Lifestyle.add(p, Lifestyle.SMITHING, 8 + Math.round(quality * 12));
        send(p, false);
    }

    public void craft(ServerPlayerEntity p, String id, double quality) {
        Recipe r = null;
        for (Recipe x : RECIPES) if (x.id().equals(id)) r = x;
        if (r == null) return;
        for (var e : r.materials().entrySet()) {
            if (count(p, item(e.getKey())) < e.getValue()) {
                p.sendMessage(Text.literal("Missing materials.").formatted(Formatting.RED), true);
                return;
            }
        }
        if (!AotRpg.WALLET.spendMarks(p, r.marks())) {
            p.sendMessage(Text.literal("You need " + r.marks() + " Marks.").formatted(Formatting.RED), true);
            return;
        }
        for (var e : r.materials().entrySet()) take(p, item(e.getKey()), e.getValue());
        quality = Math.max(0, Math.min(1, quality));
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int smith = Lifestyle.level(pr, Lifestyle.SMITHING);
        ItemStack out;
        if (r.id().equals("components")) {
            out = new ItemStack(item(r.base()), 8);
        } else {
            // Craftsmanship shifts the odds towards rarer results.
            int luck = (quality > 0.85 ? 1 : 0) + (smith >= 25 ? 1 : 0);
            Gear.Rarity rar = Gear.rollRarity(p.getRandom(), luck);
            int ilvl = Math.max(1, pr.level / 2 + smith / 2 + (int) Math.round(quality * 5));
            out = r.base().isEmpty() ? Gear.rollArmor(p.getRandom(), rar, ilvl) : Gear.rollAs(p.getRandom(), rar, ilvl, item(r.base()));
        }
        p.getInventory().offerOrDrop(out);
        p.sendMessage(Text.literal("Forged: ").formatted(Formatting.GRAY).append(out.getName().copy()), false);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_SMITHING_TABLE_USE, SoundCategory.BLOCKS, 0.9f, 1f);
        Lifestyle.add(p, Lifestyle.SMITHING, 15 + Math.round(quality * 20));
        send(p, false);
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.ForgeView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int smith = Lifestyle.level(pr, Lifestyle.SMITHING);
        List<Net.ForgeGear> gear = new ArrayList<>();
        for (int i = 0; i < p.getInventory().main.size(); i++) {
            ItemStack s = p.getInventory().main.get(i);
            if (!Gear.isGear(s)) continue;
            long[] c = cost(s);
            gear.add(new Net.ForgeGear(i, Gear.data(s).getInt("up"), c[0], (int) c[1], (int) c[2], (float) chance(s, smith, 0)));
        }
        List<Net.ForgeRecipe> recipes = new ArrayList<>();
        for (Recipe r : RECIPES) {
            StringBuilder m = new StringBuilder();
            boolean ok = true;
            for (var e : r.materials().entrySet()) {
                Item it = item(e.getKey());
                if (it == Items.AIR) continue;
                int have = count(p, it);
                if (have < e.getValue()) ok = false;
                if (m.length() > 0) m.append(", ");
                m.append(e.getValue()).append("x ").append(it.getName().getString()).append(" (").append(have).append(")");
            }
            recipes.add(new Net.ForgeRecipe(r.id(), r.title(), m.toString(), r.marks(), ok));
        }
        int iron = count(p, Items.IRON_INGOT), steel = count(p, item("dannys-aot:ultrahard_steel_ingot"));
        ServerPlayNetworking.send(p, new Net.ForgeView(gear, recipes, smith, iron, steel, open));
    }
}
