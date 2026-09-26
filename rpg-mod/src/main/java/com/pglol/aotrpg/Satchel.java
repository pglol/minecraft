package com.pglol.aotrpg;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The satchel: a separate bag for story items and supplies (food, ingredients, fish).
 * Saved in <world>/aot_rpg/satchels/<uuid>.dat and never dropped on death.
 */
public final class Satchel {
    /** How many stacks the satchel holds (its "load"). */
    public static final int SIZE = 240;
    /** Slot addresses: 0-35 the player's inventory, BAG + i the satchel's slot i. */
    public static final int BAG = 1000;
    private final Map<UUID, SimpleInventory> bags = new HashMap<>();
    /** Per player: the ODM sheath (0 and 2) and the off-hand item stashed while grips are drawn (1). */
    private final Map<UUID, SimpleInventory> gears = new HashMap<>();
    private MinecraftServer server;
    private Path dir;

    /** Cooking ingredients and other supplies that are not food themselves. */
    public static final Set<Item> SUPPLIES = Set.of(Items.WHEAT, Items.SUGAR, Items.EGG, Items.BOWL, Items.MILK_BUCKET,
        Items.BROWN_MUSHROOM, Items.RED_MUSHROOM, Items.COCOA_BEANS, Items.HONEY_BOTTLE, Items.PUMPKIN, Items.MELON,
        Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.BONE_MEAL, Items.INK_SAC, Items.FEATHER, Items.STRING,
        Items.LEATHER, Items.RABBIT_HIDE, Items.KELP, Items.SEAGRASS, Items.SALMON_BUCKET, Items.COD_BUCKET);

    public static boolean isStory(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().contains("aot_story");
    }

    public static ItemStack markStory(ItemStack s) {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, s, n -> n.putBoolean("aot_story", true));
        return s;
    }

    /** The satchel holds everything you carry beyond your loadout and armor. */
    public static boolean accepts(ItemStack s) {
        return !s.isEmpty();
    }

    public void open(MinecraftServer server) {
        this.server = server;
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("satchels");
        bags.clear();
        gears.clear();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not create satchel folder", e);
        }
    }

    public SimpleInventory get(UUID id) {
        return bags.computeIfAbsent(id, this::load);
    }

    public SimpleInventory gear(UUID id) {
        get(id);
        return gears.computeIfAbsent(id, k -> new SimpleInventory(3));
    }

    private SimpleInventory load(UUID id) {
        SimpleInventory inv = new SimpleInventory(SIZE);
        Path f = dir.resolve(AotRpg.PROFILES.activeStem(id) + ".dat");
        if (Files.exists(f)) {
            try {
                NbtCompound n = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
                inv.readNbtList(n.getList("Items", NbtElement.COMPOUND_TYPE), server.getRegistryManager());
                SimpleInventory g = new SimpleInventory(3);
                if (n.contains("Gear", NbtElement.COMPOUND_TYPE)) {
                    net.minecraft.inventory.Inventories.readNbt(n.getCompound("Gear"), g.getHeldStacks(), server.getRegistryManager());
                }
                gears.put(id, g);
            } catch (Exception e) {
                AotRpg.LOG.error("Could not read satchel {}", f, e);
            }
        }
        return inv;
    }

    public void save(UUID id) {
        SimpleInventory inv = bags.get(id);
        if (inv == null || dir == null) return;
        try {
            NbtCompound n = new NbtCompound();
            n.put("Items", inv.toNbtList(server.getRegistryManager()));
            SimpleInventory g = gears.get(id);
            if (g != null) {
                NbtCompound gn = new NbtCompound();
                net.minecraft.inventory.Inventories.writeNbt(gn, g.getHeldStacks(), server.getRegistryManager());
                n.put("Gear", gn);
            }
            NbtIo.writeCompressed(n, dir.resolve(AotRpg.PROFILES.activeStem(id) + ".dat"));
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save satchel {}", id, e);
        }
    }

    /** A character's satchel file by stem (see ProfileStore.stem). */
    public Path fileFor(String stem) {
        return dir.resolve(stem + ".dat");
    }

    public void unload(UUID id) {
        save(id);
        bags.remove(id);
        gears.remove(id);
    }

    public void saveAll() {
        for (UUID id : bags.keySet()) save(id);
    }

    /** Puts a stack in the satchel; what does not fit goes to the inventory (or the ground). */
    public void add(ServerPlayerEntity p, ItemStack stack) {
        ItemStack rest = get(p.getUuid()).addStack(stack);
        if (!rest.isEmpty()) p.giveItemStack(rest);
        if (!rest.isEmpty()) p.dropItem(rest, false);
        save(p.getUuid());
    }

    /**
     * Supplies (blades, cartridges, Ice Burst clusters) live in the satchel. While the player holds
     * gear that uses them, one stack of each sits in the inventory so reloading and refilling work;
     * otherwise they (and newly picked-up ones) go back into the satchel.
     */
    public void tickSupplies(ServerPlayerEntity p) {
        var inv = p.getInventory();
        SimpleInventory bag = get(p.getUuid());
        boolean armed = AotItems.usesSupplies(p.getMainHandStack()) || AotItems.usesSupplies(p.getOffHandStack());
        boolean changed = false;
        if (armed) {
            for (int i = 0; i < bag.size(); i++) {
                ItemStack s = bag.getStack(i);
                if (!AotItems.feeds(p.getMainHandStack(), s) && !AotItems.feeds(p.getOffHandStack(), s)) continue;
                int have = 0;
                for (ItemStack m : inv.main) if (ItemStack.areItemsAndComponentsEqual(m, s)) have += m.getCount();
                int want = s.getMaxCount() - have;
                if (want <= 0) continue;
                // Prefer the main inventory rows over the hotbar.
                for (int slot = 9; slot < 36 && want > 0 && !s.isEmpty(); slot++) {
                    ItemStack m = inv.main.get(slot);
                    if (m.isEmpty()) {
                        int k = Math.min(want, s.getCount());
                        inv.main.set(slot, s.split(k));
                        want -= k;
                        changed = true;
                    } else if (ItemStack.areItemsAndComponentsEqual(m, s) && m.getCount() < m.getMaxCount()) {
                        int k = Math.min(Math.min(want, s.getCount()), m.getMaxCount() - m.getCount());
                        m.increment(k);
                        s.decrement(k);
                        want -= k;
                        changed = true;
                    }
                }
            }
        } else if (p.currentScreenHandler == p.playerScreenHandler && p.currentScreenHandler.getCursorStack().isEmpty()) {
            // Not while a chest or the satchel is open, so manual moves are never fought.
            for (int slot = 0; slot < inv.main.size(); slot++) {
                ItemStack m = inv.main.get(slot);
                if (!AotItems.isSupply(m)) continue;
                ItemStack rest = bag.addStack(m.copy());
                if (rest.getCount() != m.getCount()) {
                    inv.main.set(slot, rest);
                    changed = true;
                }
            }
        }
        if (changed) {
            bag.markDirty();
            inv.markDirty();
        }
    }

    public void openScreen(ServerPlayerEntity p) {
        send(p, true);
    }

    // ------------------------------------------------------------------ addresses

    /** The stack at an address (inventory slot, or BAG + satchel slot). */
    public ItemStack at(ServerPlayerEntity p, int addr) {
        if (addr >= BAG) {
            SimpleInventory bag = get(p.getUuid());
            int i = addr - BAG;
            return i < bag.size() ? bag.getStack(i) : ItemStack.EMPTY;
        }
        return addr >= 0 && addr < p.getInventory().main.size() ? p.getInventory().main.get(addr) : ItemStack.EMPTY;
    }

    public void set(ServerPlayerEntity p, int addr, ItemStack s) {
        if (addr >= BAG) {
            SimpleInventory bag = get(p.getUuid());
            if (addr - BAG < bag.size()) bag.setStack(addr - BAG, s);
            bag.markDirty();
        } else if (addr >= 0 && addr < p.getInventory().main.size()) {
            p.getInventory().main.set(addr, s);
            p.getInventory().markDirty();
        }
    }

    /** Every address holding something: the inventory first, then the satchel. */
    public java.util.List<Integer> addresses(ServerPlayerEntity p) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < p.getInventory().main.size(); i++) if (!p.getInventory().main.get(i).isEmpty()) out.add(i);
        SimpleInventory bag = get(p.getUuid());
        for (int i = 0; i < bag.size(); i++) if (!bag.getStack(i).isEmpty()) out.add(BAG + i);
        return out;
    }

    // ------------------------------------------------------------------ the bag

    /**
     * The inventory rows are gone: whatever lands there (pickups, chest takes) moves into the
     * satchel, except the supplies kept at hand for reloading while armed.
     */
    public void sweep(ServerPlayerEntity p) {
        if (p.isCreative() || p.currentScreenHandler != p.playerScreenHandler || !p.currentScreenHandler.getCursorStack().isEmpty()) return;
        var inv = p.getInventory();
        SimpleInventory bag = get(p.getUuid());
        boolean armed = AotItems.usesSupplies(p.getMainHandStack()) || AotItems.usesSupplies(p.getOffHandStack());
        boolean changed = false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack m = inv.main.get(slot);
            if (m.isEmpty() || (armed && (AotItems.feeds(p.getMainHandStack(), m) || AotItems.feeds(p.getOffHandStack(), m)))) continue;
            ItemStack rest = bag.addStack(m.copy());
            if (rest.getCount() != m.getCount()) {
                inv.main.set(slot, rest);
                changed = true;
            }
        }
        if (changed) {
            bag.markDirty();
            inv.markDirty();
            send(p, false);
        }
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, Net.BagView.ID)) return;
        SimpleInventory bag = get(p.getUuid());
        java.util.List<Net.BagEntry> list = new java.util.ArrayList<>();
        for (int i = 0; i < bag.size(); i++) if (!bag.getStack(i).isEmpty()) list.add(new Net.BagEntry(i, bag.getStack(i).copy()));
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Net.BagView(list, SIZE, open));
    }

    /** Buttons in the satchel: equip, use, drop, list on the Global Market. */
    public void action(ServerPlayerEntity p, String action, int slot, long arg) {
        SimpleInventory bag = get(p.getUuid());
        if (action.equals("store")) {
            store(p, bag, (int) arg);
            return;
        }
        if (action.equals("storecursor")) {
            // An item carried on the cursor, dropped on the Satchel button.
            var h = p.currentScreenHandler;
            ItemStack c = h.getCursorStack();
            if (c.isEmpty() || isStory(c)) return;
            h.setCursorStack(bag.addStack(c.copy()));
            h.syncState();
            bag.markDirty();
            save(p.getUuid());
            send(p, false);
            return;
        }
        if (slot < 0 || slot >= bag.size()) return;
        ItemStack s = bag.getStack(slot);
        if (s.isEmpty()) return;
        switch (action) {
            case "equip" -> {
                if (arg >= 0) equipTo(p, bag, slot, s, (int) arg);
                else equip(p, bag, slot, s);
            }
            case "use" -> {
                if (!s.contains(DataComponentTypes.FOOD) && !(s.getItem() instanceof net.minecraft.item.PotionItem)) break;
                ItemStack one = s.split(1);
                ItemStack left = one.getItem().finishUsing(one, p.getWorld(), p);
                if (!left.isEmpty() && left != one) p.getInventory().offerOrDrop(left);
                p.getWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EAT,
                    net.minecraft.sound.SoundCategory.PLAYERS, 0.6f, 1f);
            }
            case "drop" -> {
                if (isStory(s)) break;
                bag.setStack(slot, ItemStack.EMPTY);
                p.dropItem(s, false, true);
            }
            case "list" -> AotRpg.EXCHANGE.list(p, BAG + slot, arg);
            default -> { }
        }
        bag.markDirty();
        save(p.getUuid());
        send(p, false);
    }

    /** Worn and loadout places: 0-8 the loadout, 40 the off hand, 100-103 feet, legs, chest, head. */
    public static final int OFF = 40, ARMOR = 100;

    public static net.minecraft.entity.EquipmentSlot armorSlot(int target) {
        return switch (target - ARMOR) {
            case 0 -> net.minecraft.entity.EquipmentSlot.FEET;
            case 1 -> net.minecraft.entity.EquipmentSlot.LEGS;
            case 2 -> net.minecraft.entity.EquipmentSlot.CHEST;
            case 3 -> net.minecraft.entity.EquipmentSlot.HEAD;
            default -> null;
        };
    }

    /** Can this item go in that place? */
    public static boolean fitsPlace(net.minecraft.entity.LivingEntity p, ItemStack s, int target) {
        if (s.isEmpty()) return true;
        if (target >= ARMOR) {
            var es = armorSlot(target);
            return es != null && p.getPreferredEquipmentSlot(s) == es;
        }
        if (target == OFF) return Loadout.isGrip(s) || s.getItem() instanceof net.minecraft.item.ShieldItem;
        return target >= 0 && target < 9 && Loadout.fits(Loadout.SLOTS[target], s);
    }

    private static ItemStack placed(ServerPlayerEntity p, int target) {
        if (target >= ARMOR) return armorSlot(target) == null ? ItemStack.EMPTY : p.getEquippedStack(armorSlot(target));
        if (target == OFF) return p.getOffHandStack();
        return target >= 0 && target < 9 ? p.getInventory().main.get(target) : ItemStack.EMPTY;
    }

    private static void place(ServerPlayerEntity p, int target, ItemStack s) {
        if (target >= ARMOR) p.equipStack(armorSlot(target), s);
        else if (target == OFF) p.setStackInHand(net.minecraft.util.Hand.OFF_HAND, s);
        else p.getInventory().main.set(target, s);
        p.getInventory().markDirty();
    }

    /** Puts a satchel item in the place the player chose; what was there comes back to the satchel. */
    private void equipTo(ServerPlayerEntity p, SimpleInventory bag, int slot, ItemStack s, int target) {
        if (target != OFF && (target < 0 || target >= 9) && armorSlot(target) == null) return;
        if (Gear.isGear(s) && !Gear.canUse(p, s)) {
            Notify.toast(p, Text.literal("Locked").formatted(net.minecraft.util.Formatting.RED),
                Text.literal("Needs level " + Gear.requiredLevel(s)), 0xC0463A, null, null);
            return;
        }
        if (!fitsPlace(p, s, target)) {
            Notify.toast(p, Text.literal("Doesn't go there").formatted(net.minecraft.util.Formatting.RED), null, 0xC0463A, null, null);
            return;
        }
        ItemStack old = placed(p, target).copy();
        place(p, target, s);
        bag.setStack(slot, old);
    }

    /** Takes what is in a loadout, off-hand or armor place back into the satchel. */
    private void store(ServerPlayerEntity p, SimpleInventory bag, int target) {
        if (target != OFF && (target < 0 || target >= 9) && armorSlot(target) == null) return;
        ItemStack s = placed(p, target);
        if (s.isEmpty() || isStory(s)) return;
        ItemStack copy = s.copy();
        ItemStack rest = bag.addStack(copy);
        if (!rest.isEmpty()) {
            Notify.toast(p, Text.literal("Satchel full").formatted(net.minecraft.util.Formatting.RED), null, 0xC0463A, null, null);
            if (rest.getCount() == s.getCount()) return;
        }
        place(p, target, rest);
        bag.markDirty();
        save(p.getUuid());
        send(p, false);
    }

    /** Armor to its slot; anything else to the loadout slot made for it (what was there comes back). */
    private void equip(ServerPlayerEntity p, SimpleInventory bag, int slot, ItemStack s) {
        if (Gear.isGear(s) && !Gear.canUse(p, s)) {
            Notify.toast(p, Text.literal("Locked").formatted(net.minecraft.util.Formatting.RED),
                Text.literal("Needs level " + Gear.requiredLevel(s)), 0xC0463A, null, null);
            return;
        }
        var es = p.getPreferredEquipmentSlot(s);
        if (es.getType() == net.minecraft.entity.EquipmentSlot.Type.HUMANOID_ARMOR) {
            ItemStack old = p.getEquippedStack(es);
            p.equipStack(es, s);
            bag.setStack(slot, old);
            return;
        }
        var inv = p.getInventory();
        int target = -1;
        for (int k = 0; k < 9 && target < 0; k++) {
            if (Loadout.SLOTS[k] != Loadout.Kind.FREE && Loadout.fits(Loadout.SLOTS[k], s) && inv.main.get(k).isEmpty()) target = k;
        }
        for (int k = 0; k < 9 && target < 0; k++) if (Loadout.SLOTS[k] != Loadout.Kind.FREE && Loadout.fits(Loadout.SLOTS[k], s)) target = k;
        for (int k = 0; k < 9 && target < 0; k++) if (Loadout.SLOTS[k] == Loadout.Kind.FREE && inv.main.get(k).isEmpty()) target = k;
        if (target < 0) target = 8;
        ItemStack old = inv.main.get(target);
        inv.main.set(target, s);
        bag.setStack(slot, old);
        inv.markDirty();
    }

    /** How many of these items the player has in satchel and inventory together. */
    public int count(ServerPlayerEntity p, Set<Item> items) {
        int n = 0;
        SimpleInventory bag = get(p.getUuid());
        for (int i = 0; i < bag.size(); i++) if (items.contains(bag.getStack(i).getItem())) n += bag.getStack(i).getCount();
        var inv = p.getInventory();
        for (int i = 0; i < inv.main.size(); i++) if (items.contains(inv.main.get(i).getItem())) n += inv.main.get(i).getCount();
        return n;
    }

    /** Removes items, satchel first. Assumes count() was checked. */
    public void take(ServerPlayerEntity p, Set<Item> items, int amount) {
        SimpleInventory bag = get(p.getUuid());
        for (int i = 0; i < bag.size() && amount > 0; i++) {
            ItemStack s = bag.getStack(i);
            if (!items.contains(s.getItem())) continue;
            int k = Math.min(amount, s.getCount());
            s.decrement(k);
            amount -= k;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.main.size() && amount > 0; i++) {
            ItemStack s = inv.main.get(i);
            if (!items.contains(s.getItem())) continue;
            int k = Math.min(amount, s.getCount());
            s.decrement(k);
            amount -= k;
        }
        bag.markDirty();
        inv.markDirty();
    }
}
