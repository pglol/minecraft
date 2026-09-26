package com.pglol.aotrpg;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Mending worn gear at home. Kept light: an iron ingot for a weapon (two if it's more than half
 * gone), leather for armor and clothing, and a small fee. Operators mend everything with
 * /aotrpg repair.
 */
public final class Repair {
    private Repair() {}

    public record Cost(int iron, int leather, long marks) { }

    public static boolean worn(ItemStack s) {
        return s.isDamageable() && s.getDamage() > 0;
    }

    /** What mending this piece takes, or null if it needs none. */
    public static Cost cost(ItemStack s) {
        if (!worn(s)) return null;
        double frac = s.getDamage() / (double) Math.max(1, s.getMaxDamage());
        int n = frac > 0.5 ? 2 : 1;
        boolean armor = s.getItem() instanceof ArmorItem || Gear.wornSlot(s) != null;
        long marks = 10 + Math.round(frac * 40) + (Gear.isGear(s) ? Gear.requiredLevel(s) : 0);
        return new Cost(armor ? 0 : n, armor ? n : 0, marks);
    }

    /** Everything you carry and wear that could need mending. */
    private static List<ItemStack> carried(ServerPlayerEntity p) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : p.getInventory().main) if (worn(s)) out.add(s);
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            ItemStack s = p.getEquippedStack(slot);
            if (worn(s)) out.add(s);
        }
        for (ItemStack s : AotRpg.SATCHEL.get(p.getUuid()).getHeldStacks()) if (worn(s)) out.add(s);
        return out;
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
        for (int a : AotRpg.SATCHEL.addresses(p)) {
            if (n <= 0) break;
            ItemStack s = AotRpg.SATCHEL.at(p, a);
            if (!s.isOf(it) || Gear.isGear(s)) continue;
            int k = Math.min(n, s.getCount());
            s.decrement(k);
            n -= k;
        }
        AotRpg.SATCHEL.get(p.getUuid()).markDirty();
        p.getInventory().markDirty();
    }

    /** Mends these pieces at home if you have the materials and Marks. */
    private static boolean mend(ServerPlayerEntity p, List<ItemStack> items) {
        if (items.isEmpty()) {
            Notify.toast(p, Text.literal("Nothing needs mending").formatted(Formatting.GRAY), null, 0x8F8A7A, "minecraft:anvil", null);
            return false;
        }
        if (!Estate.atHome(p)) {
            Notify.toast(p, Text.literal("Mend your gear at home").formatted(Formatting.RED),
                Text.literal("On your property or in your house"), 0xC0463A, "minecraft:anvil", null);
            return false;
        }
        int iron = 0, leather = 0;
        long marks = 0;
        for (ItemStack s : items) {
            Cost c = cost(s);
            iron += c.iron();
            leather += c.leather();
            marks += c.marks();
        }
        if (count(p, Items.IRON_INGOT) < iron || count(p, Items.LEATHER) < leather) {
            Notify.toast(p, Text.literal("Not enough materials").formatted(Formatting.RED),
                Text.literal("Needs " + (iron > 0 ? iron + " iron ingot" + (iron == 1 ? "" : "s") : "") + (iron > 0 && leather > 0 ? " and " : "")
                    + (leather > 0 ? leather + " leather" : "")), 0xC0463A, "minecraft:iron_ingot", null);
            return false;
        }
        if (!AotRpg.WALLET.spendMarks(p, marks)) {
            Notify.toast(p, Text.literal("Mending costs " + marks + " Marks").formatted(Formatting.RED), null, 0xC0463A, null, null);
            return false;
        }
        take(p, Items.IRON_INGOT, iron);
        take(p, Items.LEATHER, leather);
        for (ItemStack s : items) s.setDamage(0);
        AotRpg.SATCHEL.get(p.getUuid()).markDirty();
        p.getInventory().markDirty();
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.6f, 1.2f);
        Notify.toast(p, Text.literal(items.size() == 1 ? "Mended" : items.size() + " pieces mended").formatted(Formatting.GREEN),
            Text.literal("-" + marks + " Marks" + (iron > 0 ? " · " + iron + " iron" : "") + (leather > 0 ? " · " + leather + " leather" : "")),
            0x5BD35B, "minecraft:anvil", null);
        return true;
    }

    public static void one(ServerPlayerEntity p, ItemStack s) {
        if (worn(s)) mend(p, List.of(s));
    }

    public static void all(ServerPlayerEntity p) {
        mend(p, carried(p));
    }

    /** Operators: everything mended, free, anywhere. Returns how many pieces. */
    public static int free(ServerPlayerEntity p) {
        List<ItemStack> items = carried(p);
        for (ItemStack s : items) s.setDamage(0);
        AotRpg.SATCHEL.get(p.getUuid()).markDirty();
        p.getInventory().markDirty();
        AotRpg.SATCHEL.send(p, false);
        return items.size();
    }
}
