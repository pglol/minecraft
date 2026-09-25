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
    public static final int SIZE = 36;
    private final Map<UUID, SimpleInventory> bags = new HashMap<>();
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

    /** What the satchel holds: story items, anything edible and supplies. */
    public static boolean accepts(ItemStack s) {
        return !s.isEmpty() && (isStory(s) || s.contains(DataComponentTypes.FOOD) || SUPPLIES.contains(s.getItem()));
    }

    public void open(MinecraftServer server) {
        this.server = server;
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("satchels");
        bags.clear();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not create satchel folder", e);
        }
    }

    public SimpleInventory get(UUID id) {
        return bags.computeIfAbsent(id, this::load);
    }

    private SimpleInventory load(UUID id) {
        SimpleInventory inv = new SimpleInventory(SIZE);
        Path f = dir.resolve(id + ".dat");
        if (Files.exists(f)) {
            try {
                NbtCompound n = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
                inv.readNbtList(n.getList("Items", NbtElement.COMPOUND_TYPE), server.getRegistryManager());
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
            NbtIo.writeCompressed(n, dir.resolve(id + ".dat"));
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save satchel {}", id, e);
        }
    }

    public void unload(UUID id) {
        save(id);
        bags.remove(id);
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

    public void openScreen(ServerPlayerEntity p) {
        SimpleInventory bag = get(p.getUuid());
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, pl) -> new SatchelHandler(syncId, inv, bag), Text.literal("Satchel")));
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
