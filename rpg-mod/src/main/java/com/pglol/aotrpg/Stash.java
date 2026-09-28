package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stash. Its size is your homes' storage: every chest and barrel counted in when the home was
 * bought (and those a cellar or its bays add), the very same chests, so what you leave at home is
 * in your stash and the other way round. On top of that, rows bought with Salvage, a currency only
 * grinding earns (titan kills, and far more for getting out of an Extraction alive).
 */
public final class Stash {
    private Stash() {}

    public static final int ROW = 9, PAGE = 54, MAX_ROWS = 30;
    private static final Map<String, SimpleInventory> lockers = new HashMap<>();
    private static Path dir;

    public static void open(MinecraftServer server) {
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("lockers");
        lockers.clear();
    }

    /** Chests and barrels (27 slots each) are stash; anything else is just furniture. */
    static boolean counts(BlockEntity be) {
        return (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) && ((Inventory) be).size() % ROW == 0;
    }

    /** What the next row of stash costs in Salvage. */
    public static long rowCost(Profile pr) {
        return 80 + 40L * pr.stashRows;
    }

    private static SimpleInventory locker(ServerPlayerEntity p) {
        String stem = AotRpg.PROFILES.activeStem(p.getUuid());
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int size = Math.max(0, Math.min(MAX_ROWS, pr.stashRows)) * ROW;
        SimpleInventory inv = lockers.get(stem);
        if (inv == null || inv.size() != size) {
            SimpleInventory n = new SimpleInventory(size);
            if (inv != null) {
                for (int i = 0; i < Math.min(inv.size(), size); i++) n.setStack(i, inv.getStack(i));
            } else {
                try {
                    Path f = dir.resolve(stem + ".dat");
                    if (Files.exists(f)) {
                        NbtCompound tag = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
                        DefaultedList<ItemStack> list = DefaultedList.ofSize(tag.getInt("Size"), ItemStack.EMPTY);
                        Inventories.readNbt(tag, list, p.getRegistryManager());
                        for (int i = 0; i < Math.min(list.size(), size); i++) n.setStack(i, list.get(i));
                    }
                } catch (Exception e) {
                    AotRpg.LOG.error("Could not read stash locker " + stem, e);
                }
            }
            inv = n;
            lockers.put(stem, inv);
        }
        return inv;
    }

    private static void saveLocker(ServerPlayerEntity p, SimpleInventory inv) {
        try {
            Files.createDirectories(dir);
            NbtCompound tag = new NbtCompound();
            DefaultedList<ItemStack> list = DefaultedList.ofSize(inv.size(), ItemStack.EMPTY);
            for (int i = 0; i < inv.size(); i++) list.set(i, inv.getStack(i));
            tag.putInt("Size", inv.size());
            Inventories.writeNbt(tag, list, p.getRegistryManager());
            NbtIo.writeCompressed(tag, dir.resolve(AotRpg.PROFILES.activeStem(p.getUuid()) + ".dat"));
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save stash locker", e);
        }
    }

    /** One slot of the stash: a container and a slot in it. */
    private record Cell(Inventory inv, int slot) { }

    /** The whole stash laid out flat: home containers first, then the bought rows. */
    private static List<Cell> layout(ServerPlayerEntity p, Set<ChunkPos> chunks) {
        List<Cell> cells = new ArrayList<>();
        ServerWorld hw = AotRpg.HOMES.stashWorld();
        if (hw != null) {
            for (BlockPos bp : AotRpg.HOMES.stashSpots(p)) {
                BlockEntity be = hw.getBlockEntity(bp);
                if (!counts(be)) continue;
                Inventory inv = (Inventory) be;
                for (int i = 0; i < inv.size(); i++) cells.add(new Cell(inv, i));
            }
        }
        SimpleInventory l = locker(p);
        for (int i = 0; i < l.size(); i++) cells.add(new Cell(l, i));
        return cells;
    }

    /** {used, capacity} slots. */
    public static int[] usage(ServerPlayerEntity p) {
        List<Cell> cells = layout(p, null);
        int used = 0;
        for (Cell c : cells) if (!c.inv().getStack(c.slot()).isEmpty()) used++;
        return new int[] {used, cells.size()};
    }


    /** Who has the stash open (it only works where it was opened: the balloon, or your own home). */
    private static final Set<java.util.UUID> open = new java.util.HashSet<>();

    /** Where the stash can be used: aboard the balloon, or inside your own home. */
    public static boolean usable(ServerPlayerEntity p) {
        return Extraction.inLobby(p) || p.getWorld().getRegistryKey() == Homes.WORLD && AotRpg.HOMES.canBuild(p, p.getBlockPos());
    }

    /** Opens the stash screen: stash and satchel side by side. */
    public static void show(ServerPlayerEntity p, int ignored) {
        if (!usable(p)) return;
        open.add(p.getUuid());
        p.playSoundToPlayer(SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.BLOCKS, 0.8f, 1f);
        AotRpg.SATCHEL.send(p, false);
        send(p, true);
    }

    public static void send(ServerPlayerEntity p, boolean openIt) {
        if (!ServerPlayNetworking.canSend(p, Net.StashView.ID)) return;
        List<Cell> cells = layout(p, null);
        List<Net.BagEntry> items = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            ItemStack s = cells.get(i).inv().getStack(cells.get(i).slot());
            if (!s.isEmpty()) items.add(new Net.BagEntry(i, s.copy()));
        }
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        ServerPlayNetworking.send(p, new Net.StashView(items, cells.size(), pr.salvage,
            pr.stashRows >= MAX_ROWS ? -1 : rowCost(pr), openIt));
    }

    private static void dirty(List<Cell> cells) {
        Set<Inventory> seen = new java.util.HashSet<>();
        for (Cell c : cells) if (seen.add(c.inv())) c.inv().markDirty();
    }

    /** Buttons and clicks in the stash screen. */
    public static void action(ServerPlayerEntity p, String action, int slot) {
        if (action.equals("close")) {
            open.remove(p.getUuid());
            return;
        }
        if (!open.contains(p.getUuid()) || !usable(p)) return;
        List<Cell> cells = layout(p, null);
        SimpleInventory bag = AotRpg.SATCHEL.get(p.getUuid());
        switch (action) {
            // Satchel slot -> stash: stacks up with what's there, then the first free slot.
            case "put" -> {
                if (slot < 0 || slot >= bag.size()) return;
                ItemStack s = bag.getStack(slot);
                if (s.isEmpty() || Satchel.isStory(s)) return;
                ItemStack rest = deposit(cells, s.copy());
                if (rest.getCount() == s.getCount()) {
                    Notify.toast(p, Text.literal("Your stash is full").formatted(net.minecraft.util.Formatting.RED), null, 0xC0463A, "minecraft:barrel", null);
                    return;
                }
                bag.setStack(slot, rest);
                bag.markDirty();
            }
            // Stash slot -> satchel.
            case "take" -> {
                if (slot < 0 || slot >= cells.size()) return;
                Cell c = cells.get(slot);
                ItemStack s = c.inv().getStack(c.slot());
                if (s.isEmpty()) return;
                ItemStack rest = bag.addStack(s.copy());
                if (rest.getCount() == s.getCount()) {
                    Notify.toast(p, Text.literal("Your satchel is full").formatted(net.minecraft.util.Formatting.RED), null, 0xC0463A, "minecraft:bundle", null);
                    return;
                }
                c.inv().setStack(c.slot(), rest);
                bag.markDirty();
            }
            // Stash slot -> worn or on the loadout bar (what was there goes back in its place).
            // Things with no place there (materials, supplies) go in the satchel instead.
            case "equip" -> {
                if (slot < 0 || slot >= cells.size()) return;
                Cell c = cells.get(slot);
                ItemStack s = c.inv().getStack(c.slot());
                if (s.isEmpty()) return;
                if (wearable(p, s)) AotRpg.SATCHEL.equip(p, c.inv(), c.slot(), s);
                else {
                    ItemStack rest = bag.addStack(s.copy());
                    if (rest.getCount() == s.getCount()) {
                        Notify.toast(p, Text.literal("Your satchel is full").formatted(net.minecraft.util.Formatting.RED), null, 0xC0463A, "minecraft:bundle", null);
                        return;
                    }
                    c.inv().setStack(c.slot(), rest);
                    bag.markDirty();
                }
            }
            // A worn or loadout place (0-8, 40 off hand, 100-103 armor) -> stash.
            case "unequip" -> {
                if (slot != Satchel.OFF && (slot < 0 || slot >= 9) && Satchel.armorSlot(slot) == null) return;
                int sh = Loadout.sheathFor(p, slot, ItemStack.EMPTY);
                ItemStack s = sh >= 0 ? Loadout.sheathStack(p, sh) : Satchel.placed(p, slot);
                if (s.isEmpty() || Satchel.isStory(s)) return;
                ItemStack rest = deposit(cells, s.copy());
                if (rest.getCount() == s.getCount()) {
                    Notify.toast(p, Text.literal("Your stash is full").formatted(net.minecraft.util.Formatting.RED), null, 0xC0463A, "minecraft:barrel", null);
                    return;
                }
                if (sh >= 0) Loadout.setSheath(p, sh, rest);
                else Satchel.place(p, slot, rest);
            }
            // Tidies the stash: like with like stacked together, best first.
            case "sort" -> {
                List<ItemStack> all = new ArrayList<>();
                for (Cell c : cells) {
                    ItemStack s = c.inv().getStack(c.slot());
                    if (s.isEmpty()) continue;
                    boolean merged = false;
                    for (ItemStack a : all) {
                        if (ItemStack.areItemsAndComponentsEqual(a, s) && a.getCount() + s.getCount() <= a.getMaxCount()) {
                            a.increment(s.getCount());
                            merged = true;
                            break;
                        }
                    }
                    if (!merged) all.add(s.copy());
                }
                all.sort(java.util.Comparator.comparingInt((ItemStack s) -> Gear.isGear(s) ? 0 : 1)
                    .thenComparingInt(s -> Gear.isGear(s) ? -Gear.requiredLevel(s) : 0)
                    .thenComparing(s -> net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString()));
                for (int i = 0; i < cells.size(); i++) cells.get(i).inv().setStack(cells.get(i).slot(), i < all.size() ? all.get(i) : ItemStack.EMPTY);
                p.playSoundToPlayer(SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.8f, 1f);
            }
            case "expand" -> expand(p);
            case "scrap_stash" -> {
                if (slot < 0 || slot >= cells.size()) return;
                Cell c = cells.get(slot);
                ItemStack s = c.inv().getStack(c.slot());
                if (!Gear.real(s)) return;
                c.inv().setStack(c.slot(), ItemStack.EMPTY);
                disassemble(p, s);
            }
            case "scrap_bag" -> {
                if (slot < 0 || slot >= bag.size()) return;
                ItemStack s = bag.getStack(slot);
                if (!Gear.real(s)) return;
                bag.setStack(slot, ItemStack.EMPTY);
                bag.markDirty();
                disassemble(p, s);
            }
            default -> { return; }
        }
        dirty(cells);
        AotRpg.SATCHEL.save(p.getUuid());
        saveLocker(p, locker(p));
        AotRpg.SATCHEL.send(p, false);
        send(p, false);
    }

    /** Has a place on you: armor, a grip, or something a named loadout slot is made for. */
    private static boolean wearable(ServerPlayerEntity p, ItemStack s) {
        if (Loadout.isGrip(s)) return true;
        if (p.getPreferredEquipmentSlot(s).getType() == net.minecraft.entity.EquipmentSlot.Type.HUMANOID_ARMOR) return true;
        for (Loadout.Kind k : Loadout.SLOTS) if (k != Loadout.Kind.FREE && Loadout.fits(k, s)) return true;
        return Gear.isGear(s);
    }

    /** Into the stash: stacked onto what's there, then the first free slots. Returns what didn't fit. */
    private static ItemStack deposit(List<Cell> cells, ItemStack rest) {
        for (Cell c : cells) {
            if (rest.isEmpty()) break;
            ItemStack t = c.inv().getStack(c.slot());
            if (!t.isEmpty() && ItemStack.areItemsAndComponentsEqual(t, rest) && t.getCount() < t.getMaxCount()) {
                int n = Math.min(rest.getCount(), t.getMaxCount() - t.getCount());
                t.increment(n);
                rest.decrement(n);
            }
        }
        for (Cell c : cells) {
            if (rest.isEmpty()) break;
            if (c.inv().getStack(c.slot()).isEmpty()) {
                c.inv().setStack(c.slot(), rest);
                rest = ItemStack.EMPTY;
            }
        }
        return rest;
    }

    /** One item into this player's stash (the satchel if the stash is full). */
    public static void store(ServerPlayerEntity p, ItemStack s) {
        List<Cell> cells = layout(p, null);
        ItemStack rest = deposit(cells, s);
        dirty(cells);
        saveLocker(p, locker(p));
        if (!rest.isEmpty()) AotRpg.SATCHEL.add(p, rest);
        if (open.contains(p.getUuid())) send(p, false);
    }

    /**
     * Empty pockets: everything carried, worn, sheathed or in the satchel goes into the stash (the
     * balloon's rule: you board with nothing and kit out from storage). What the stash can't hold
     * stays where it was.
     */
    public static void bankAll(ServerPlayerEntity p) {
        List<Cell> cells = layout(p, null);
        if (cells.isEmpty()) return;
        int moved = 0, left = 0;
        var inv = p.getInventory();
        for (var list : List.of(inv.main, inv.armor, inv.offHand)) {
            for (int i = 0; i < list.size(); i++) {
                ItemStack s = list.get(i);
                if (s.isEmpty() || Satchel.isStory(s)) continue;
                ItemStack rest = deposit(cells, s.copy());
                if (rest.getCount() != s.getCount()) moved++;
                if (!rest.isEmpty()) left++;
                list.set(i, rest);
            }
        }
        inv.markDirty();
        int[] sh = {0, 0};
        Loadout.bankSheath(p, s -> {
            ItemStack rest = deposit(cells, s);
            sh[0]++;
            if (!rest.isEmpty()) sh[1]++;
            return rest;
        });
        moved += sh[0] - sh[1];
        left += sh[1];
        SimpleInventory bag = AotRpg.SATCHEL.get(p.getUuid());
        for (int i = 0; i < bag.size(); i++) {
            ItemStack s = bag.getStack(i);
            if (s.isEmpty() || Satchel.isStory(s)) continue;
            ItemStack rest = deposit(cells, s.copy());
            if (rest.getCount() != s.getCount()) moved++;
            if (!rest.isEmpty()) left++;
            bag.setStack(i, rest);
        }
        bag.markDirty();
        dirty(cells);
        AotRpg.SATCHEL.save(p.getUuid());
        saveLocker(p, locker(p));
        AotRpg.SATCHEL.send(p, false);
        if (left > 0) {
            Notify.toast(p, Text.literal("Stash full").formatted(net.minecraft.util.Formatting.RED),
                Text.literal(left + " kept on you"), 0xC0463A, "minecraft:barrel", null);
        } else if (moved > 0) {
            p.playSoundToPlayer(SoundEvents.BLOCK_BARREL_CLOSE, SoundCategory.BLOCKS, 0.7f, 1f);
        }
    }

    /**
     * Disassembling gear: it's broken down into Salvage and metal (iron, and ultrahard steel from
     * Epic up), more for rarer and higher pieces. The metal goes in the satchel.
     */
    public static void disassemble(ServerPlayerEntity p, ItemStack s) {
        int rar = Gear.rarityOf(s), lvl = Gear.requiredLevel(s);
        long salvage = 2 + rar * 3L + lvl / 8 + (rar >= 5 ? 20 : 0);
        int iron = 1 + rar, steel = rar >= 3 ? rar - 2 : 0;
        earn(p, salvage);
        AotRpg.PROFILES.save(p.getUuid());
        AotRpg.SATCHEL.add(p, new ItemStack(net.minecraft.item.Items.IRON_INGOT, iron));
        var st = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of("dannys-aot", "ultrahard_steel_ingot"));
        if (steel > 0 && st != net.minecraft.item.Items.AIR) AotRpg.SATCHEL.add(p, new ItemStack(st, steel));
        p.playSoundToPlayer(SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.PLAYERS, 0.8f, 1.1f);
        Notify.toast(p, Text.literal("Disassembled").formatted(net.minecraft.util.Formatting.GOLD),
            Text.literal("+" + salvage + " Salvage  \u00B7  " + iron + " iron" + (steel > 0 ? "  \u00B7  " + steel + " steel" : "")), 0xE0B96A,
            "minecraft:grindstone", null);
    }

    /** Buys one more row of stash with Salvage. */
    public static void expand(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (pr.stashRows >= MAX_ROWS) return;
        long cost = rowCost(pr);
        if (pr.salvage < cost) {
            Notify.toast(p, Text.literal("Not enough Salvage").formatted(net.minecraft.util.Formatting.RED), null, 0xC0463A, "aot_rpg:gas_refueler", null);
            return;
        }
        pr.salvage -= cost;
        pr.stashRows++;
        AotRpg.PROFILES.save(p.getUuid());
        Reveal.show(p, "STASH EXPANDED", "+" + ROW + " slots", "minecraft:barrel", Math.min(4, 1 + pr.stashRows / 6));
    }

    /** Salvage earned (quietly: it shows in the lobby). */
    public static void earn(ServerPlayerEntity p, long n) {
        if (n <= 0) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        pr.salvage += n;
    }
}
