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
        new Recipe("armor", "Armor piece", mats("minecraft:iron_ingot", 8, "dannys-aot:ultrahard_leather", 1), 250, ""),
        new Recipe("components", "Blade components x8", mats("minecraft:iron_ingot", 2), 20, "dannys-aot:blade_component"),
        new Recipe("refueler", "Gas Refueler (Engineer)", mats("minecraft:iron_ingot", 6, "minecraft:copper_ingot", 4, "dannys-aot:ultrahard_steel_ingot", 1), 350,
            "aot_rpg:gas_refueler"));

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
        for (int a : AotRpg.SATCHEL.addresses(p)) {
            ItemStack s = AotRpg.SATCHEL.at(p, a);
            if (s.isOf(it) && !Gear.isGear(s)) n += s.getCount();
        }
        return n;
    }

    private static void take(ServerPlayerEntity p, Item it, int n) {
        AotRpg.SATCHEL.get(p.getUuid()).markDirty();
        for (int a : AotRpg.SATCHEL.addresses(p)) {
            ItemStack s = AotRpg.SATCHEL.at(p, a);
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

    /** Tempering goes no higher than this: three levels past your own. */
    public static int cap(ServerPlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid()).level + 3;
    }

    /** What the next temper costs, in Marks: more for higher levels and rarer gear. */
    public static long temperCost(ItemStack s) {
        int target = Gear.requiredLevel(s) + 1;
        return Math.round((25 + 6.0 * target) * (1 + 0.35 * Gear.rarityOf(s)) / 5.0) * 5;
    }

    /**
     * Tempering: the piece rises a level (its stats with it), never past your level + 3, so the gear
     * you love keeps pace with you. Three hammer blows; land all of them in the heart of the heat
     * and it rises two. It never fails.
     */
    public void upgrade(ServerPlayerEntity p, int slot, double quality) {
        ItemStack s = AotRpg.SATCHEL.at(p, slot);
        if (!Gear.real(s)) return;
        int lvl = Gear.requiredLevel(s), cap = cap(p);
        if (lvl >= cap) {
            Notify.toast(p, Text.literal("As strong as you are").formatted(Formatting.GOLD),
                Text.literal("Level up to temper it further"), 0xE0B96A, "minecraft:anvil", null);
            return;
        }
        long c = temperCost(s);
        if (!AotRpg.WALLET.spendMarks(p, c)) {
            Notify.toast(p, Text.literal("Not enough Marks").formatted(Formatting.RED), null, 0xC0463A, "minecraft:anvil", null);
            return;
        }
        quality = Math.max(0, Math.min(1, quality));
        boolean perfect = quality >= 0.85;
        int to = Math.min(cap, lvl + (perfect ? 2 : 1));
        Gear.setLevel(s, to);
        AotRpg.SATCHEL.get(p.getUuid()).markDirty();
        p.getInventory().markDirty();
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.8f, perfect ? 1.4f : 1.1f);
        if (perfect) p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.6f, 1.6f);
        Notify.toast(p, Text.literal((perfect ? "PERFECT  ·  " : "") + "Lv " + lvl + " → " + to).formatted(perfect ? Formatting.GOLD : Formatting.YELLOW, Formatting.BOLD),
            s.getName().copy(), perfect ? 0xF2C14E : 0xE0B96A, net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString(), null);
        Lifestyle.add(p, Lifestyle.SMITHING, 8 + Math.round(quality * 12));
        AotRpg.TASKS.count(p, Tasks.FORGE, 1);
        send(p, false);
    }

    public void craft(ServerPlayerEntity p, String id, double quality) {
        Recipe r = null;
        for (Recipe x : RECIPES) if (x.id().equals(id)) r = x;
        if (r == null) return;
        if (r.id().equals("refueler") && !AotRpg.PROFILES.get(p.getUuid()).has(Skill.ENG_WORKSHOP)) {
            p.sendMessage(Text.literal("Engineers only: learn Field Workshop (Engineer tree).").formatted(Formatting.RED), true);
            return;
        }
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
        } else if (r.id().equals("refueler")) {
            out = new ItemStack(item(r.base()));
        } else {
            // Craftsmanship shifts the odds towards rarer results.
            int luck = (quality > 0.85 ? 1 : 0) + (smith >= 25 ? 1 : 0);
            Gear.Rarity rar = Gear.rollRarity(p.getRandom(), luck);
            // Forged gear tops out 3 levels above the smith.
            int ilvl = Math.max(1, Math.min(pr.level + 3, pr.level / 2 + smith / 2 + (int) Math.round(quality * 5)));
            out = r.base().isEmpty() ? Gear.rollArmor(p.getRandom(), rar, ilvl) : Gear.rollAs(p.getRandom(), rar, ilvl, item(r.base()));
        }
        p.getInventory().offerOrDrop(out);
        p.sendMessage(Text.literal("Forged: ").formatted(Formatting.GRAY).append(out.getName().copy()), false);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_SMITHING_TABLE_USE, SoundCategory.BLOCKS, 0.9f, 1f);
        Lifestyle.add(p, Lifestyle.SMITHING, 15 + Math.round(quality * 20));
        AotRpg.TASKS.count(p, Tasks.FORGE, 1);
        send(p, false);
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.ForgeView.ID)) return;
        AotRpg.SATCHEL.send(p, false);
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int smith = Lifestyle.level(pr, Lifestyle.SMITHING);
        List<Net.ForgeGear> gear = new ArrayList<>();
        int cap = cap(p);
        for (int i : AotRpg.SATCHEL.addresses(p)) {
            ItemStack s = AotRpg.SATCHEL.at(p, i);
            if (!Gear.real(s)) continue;
            gear.add(new Net.ForgeGear(i, Gear.requiredLevel(s), cap, temperCost(s), Gear.rarityOf(s)));
        }
        // Best first: the rarest, then the highest.
        gear.sort((a, b) -> a.rarity() != b.rarity() ? b.rarity() - a.rarity() : b.level() - a.level());
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
