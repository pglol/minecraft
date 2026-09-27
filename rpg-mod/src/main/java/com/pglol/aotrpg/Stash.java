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
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
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
                ChunkPos cp = new ChunkPos(bp);
                if (chunks != null && chunks.add(cp)) hw.setChunkForced(cp.x, cp.z, true);
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
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        List<Cell> cells = layout(p, chunks);
        release(chunks);
        int used = 0;
        for (Cell c : cells) if (!c.inv().getStack(c.slot()).isEmpty()) used++;
        return new int[] {used, cells.size()};
    }

    private static void release(Set<ChunkPos> chunks) {
        ServerWorld hw = AotRpg.HOMES.stashWorld();
        if (hw == null) return;
        for (ChunkPos cp : chunks) hw.setChunkForced(cp.x, cp.z, false);
    }

    /** A page of the stash as a plain chest screen. */
    private static final class Page implements Inventory {
        final List<Cell> cells;
        final Set<ChunkPos> chunks;
        final ServerPlayerEntity owner;
        final SimpleInventory locker;

        Page(ServerPlayerEntity owner, List<Cell> cells, Set<ChunkPos> chunks, SimpleInventory locker) {
            this.owner = owner;
            this.cells = cells;
            this.chunks = chunks;
            this.locker = locker;
        }

        public int size() { return cells.size(); }

        public boolean isEmpty() {
            for (Cell c : cells) if (!c.inv().getStack(c.slot()).isEmpty()) return false;
            return true;
        }

        public ItemStack getStack(int i) { return cells.get(i).inv().getStack(cells.get(i).slot()); }

        public ItemStack removeStack(int i, int n) {
            ItemStack s = cells.get(i).inv().removeStack(cells.get(i).slot(), n);
            markDirty();
            return s;
        }

        public ItemStack removeStack(int i) {
            ItemStack s = cells.get(i).inv().removeStack(cells.get(i).slot());
            markDirty();
            return s;
        }

        public void setStack(int i, ItemStack s) {
            cells.get(i).inv().setStack(cells.get(i).slot(), s);
            markDirty();
        }

        public void markDirty() {
            for (Cell c : cells) if (c.inv() != locker) c.inv().markDirty();
        }

        public boolean canPlayerUse(PlayerEntity p) { return p == owner && Extraction.inLobby(owner); }

        public void onClose(PlayerEntity p) {
            saveLocker(owner, locker);
            release(chunks);
        }

        public void clear() {
            for (Cell c : cells) c.inv().setStack(c.slot(), ItemStack.EMPTY);
        }
    }

    public static void show(ServerPlayerEntity p, int page) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        List<Cell> all = layout(p, chunks);
        if (all.isEmpty()) {
            release(chunks);
            Notify.toast(p, Text.literal("Your stash is empty space").formatted(net.minecraft.util.Formatting.GOLD),
                Text.literal("Buy a home: its chests and barrels are your stash"), 0xE0B96A, "minecraft:barrel", null);
            return;
        }
        int pages = (all.size() + PAGE - 1) / PAGE;
        int pg = Math.max(0, Math.min(pages - 1, page));
        List<Cell> cells = new ArrayList<>(all.subList(pg * PAGE, Math.min(all.size(), pg * PAGE + PAGE)));
        int rows = Math.max(1, cells.size() / ROW);
        int used = 0;
        for (Cell c : all) if (!c.inv().getStack(c.slot()).isEmpty()) used++;
        Page inv = new Page(p, cells, chunks, locker(p));
        ScreenHandlerType<?> type = switch (rows) {
            case 1 -> ScreenHandlerType.GENERIC_9X1;
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            case 3 -> ScreenHandlerType.GENERIC_9X3;
            case 4 -> ScreenHandlerType.GENERIC_9X4;
            case 5 -> ScreenHandlerType.GENERIC_9X5;
            default -> ScreenHandlerType.GENERIC_9X6;
        };
        int r = rows;
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, pinv, pl) -> new GenericContainerScreenHandler(type, id, pinv, inv, r),
            Text.literal("Stash")));
        p.playSoundToPlayer(SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.BLOCKS, 0.8f, 1f);
        if (ServerPlayNetworking.canSend(p, Net.StashInfo.ID)) ServerPlayNetworking.send(p, new Net.StashInfo(pg, pages, used, all.size()));
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
