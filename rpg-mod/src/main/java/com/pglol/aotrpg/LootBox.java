package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Searching a container out on a run, the way a scavenger does it: open it and what's inside
 * shows up one piece at a time as you rummage (longer for the better finds), then take what you
 * want into your pockets, or stash your own things in it. Whoever else opens the same barrel sees
 * the same contents, but does their own searching.
 */
public final class LootBox {
    private LootBox() {}

    private record Open(ServerWorld world, BlockPos pos, String title) { }

    private static final Map<UUID, Open> open = new HashMap<>();
    /** What each player has already turned up, per container ("uuid@pos" -> slots). */
    private static final Map<String, Set<Integer>> found = new HashMap<>();
    /** The slot being searched now and when it turns up: {slot, doneAt, took}. */
    private static final Map<UUID, long[]> search = new HashMap<>();

    private static String key(ServerPlayerEntity p, BlockPos pos) {
        return p.getUuid() + "@" + pos.asLong();
    }

    private static Inventory inv(Open o) {
        return o.world().getBlockEntity(o.pos()) instanceof Inventory i ? i : null;
    }

    /** Opens (or re-opens) a container for searching. */
    public static void show(ServerPlayerEntity p, BlockPos pos, String title) {
        if (!ServerPlayNetworking.canSend(p, Net.LootView.ID)) return;
        Open o = new Open(p.getServerWorld(), pos.toImmutable(), title);
        if (inv(o) == null) return;
        open.put(p.getUuid(), o);
        search.remove(p.getUuid());
        p.playSoundToPlayer(SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.BLOCKS, 0.7f, 1f);
        next(p, o);
        send(p, o, true);
    }

    /** Forgets a player's searches (their run is over). */
    public static void forget(UUID id) {
        open.remove(id);
        search.remove(id);
        String pre = id + "@";
        found.keySet().removeIf(k -> k.startsWith(pre));
    }

    /** How long a piece takes to turn up: quick for supplies, longer the better it is. */
    private static long searchMs(ItemStack s) {
        return Gear.isGear(s) ? 500 + 260L * Gear.rarityOf(s) : 320;
    }

    /** Starts searching the next unseen slot, if any. */
    private static void next(ServerPlayerEntity p, Open o) {
        Inventory inv = inv(o);
        if (inv == null) return;
        Set<Integer> seen = found.computeIfAbsent(key(p, o.pos()), k -> new HashSet<>());
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || seen.contains(i)) continue;
            long took = searchMs(s);
            search.put(p.getUuid(), new long[] {i, System.currentTimeMillis() + took, took});
            return;
        }
        search.remove(p.getUuid());
    }

    private static void send(ServerPlayerEntity p, Open o, boolean openIt) {
        Inventory inv = inv(o);
        if (inv == null) {
            close(p);
            return;
        }
        Set<Integer> seen = found.computeIfAbsent(key(p, o.pos()), k -> new HashSet<>());
        List<Net.BagEntry> items = new ArrayList<>();
        List<Integer> hidden = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (seen.contains(i)) items.add(new Net.BagEntry(i, s.copy()));
            else hidden.add(i);
        }
        long[] sr = search.get(p.getUuid());
        ServerPlayNetworking.send(p, new Net.LootView(o.pos().asLong(), o.title(), inv.size(), items, hidden,
            sr == null ? -1 : (int) sr[0], sr == null ? 0 : (int) sr[2], openIt, false));
    }

    private static void close(ServerPlayerEntity p) {
        open.remove(p.getUuid());
        search.remove(p.getUuid());
        if (ServerPlayNetworking.canSend(p, Net.LootView.ID)) {
            ServerPlayNetworking.send(p, new Net.LootView(0, "", 0, List.of(), List.of(), -1, 0, false, true));
        }
    }

    /** Everyone looking into this container sees it as it is now. */
    private static void refresh(MinecraftServer server, Open changed) {
        for (var e : new ArrayList<>(open.entrySet())) {
            Open o = e.getValue();
            if (o.world() != changed.world() || !o.pos().equals(changed.pos())) continue;
            ServerPlayerEntity v = server.getPlayerManager().getPlayer(e.getKey());
            if (v == null) continue;
            long[] sr = search.get(v.getUuid());
            Inventory inv = inv(o);
            // The piece they were searching was taken from under them: move on.
            if (sr != null && inv != null && inv.getStack((int) sr[0]).isEmpty()) next(v, o);
            send(v, o, false);
        }
    }

    public static void tick(MinecraftServer server) {
        if (open.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var e : new ArrayList<>(open.entrySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            Open o = e.getValue();
            if (p == null) {
                open.remove(e.getKey());
                search.remove(e.getKey());
                continue;
            }
            // Walked off, fell, or the container is gone.
            if (!p.isAlive() || p.isSpectator() || p.getServerWorld() != o.world() || inv(o) == null
                || p.squaredDistanceTo(o.pos().toCenterPos()) > 6 * 6) {
                close(p);
                continue;
            }
            long[] sr = search.get(p.getUuid());
            if (sr == null || now < sr[1]) continue;
            Inventory inv = inv(o);
            ItemStack s = inv.getStack((int) sr[0]);
            found.computeIfAbsent(key(p, o.pos()), k -> new HashSet<>()).add((int) sr[0]);
            if (!s.isEmpty()) {
                int r = Gear.isGear(s) ? Gear.rarityOf(s) : 0;
                if (r >= 4) {
                    // A jackpot: it lands like one.
                    p.playSoundToPlayer(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, r >= 5 ? 0.7f : 1f);
                    p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, r >= 5 ? 0.9f : 0.5f, r >= 5 ? 0.8f : 1.2f);
                    if (r >= 5) p.playSoundToPlayer(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.35f, 1.6f);
                    Notify.toast(p, Text.literal(r >= 5 ? "MYTHIC FIND" : "LEGENDARY FIND").formatted(r >= 5 ? Formatting.RED : Formatting.GOLD, Formatting.BOLD),
                        s.getName().copy(), r >= 5 ? 0xFF2A2A : 0xFFB020, net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString(), null);
                    var at = o.pos().toCenterPos();
                    o.world().spawnParticles(p, r >= 5 ? net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING : net.minecraft.particle.ParticleTypes.WAX_OFF,
                        true, at.x, at.y + 0.8, at.z, 40, 0.4, 0.4, 0.4, 0.3);
                } else {
                    p.playSoundToPlayer(SoundEvents.ITEM_BUNDLE_REMOVE_ONE, SoundCategory.PLAYERS, 0.5f, 1.2f + 0.1f * r);
                }
            }
            next(p, o);
            send(p, o, false);
        }
    }

    public static void action(ServerPlayerEntity p, String action, int slot) {
        Open o = open.get(p.getUuid());
        if (action.equals("close")) {
            if (o != null) p.playSoundToPlayer(SoundEvents.BLOCK_BARREL_CLOSE, SoundCategory.BLOCKS, 0.6f, 1f);
            open.remove(p.getUuid());
            search.remove(p.getUuid());
            return;
        }
        if (o == null) return;
        Inventory inv = inv(o);
        if (inv == null) return;
        Set<Integer> seen = found.computeIfAbsent(key(p, o.pos()), k -> new HashSet<>());
        switch (action) {
            case "take" -> {
                if (slot < 0 || slot >= inv.size() || !seen.contains(slot)) return;
                if (!take(p, inv, slot)) return;
            }
            case "take_all" -> {
                boolean any = false;
                for (int i = 0; i < inv.size(); i++) if (seen.contains(i) && !inv.getStack(i).isEmpty() && take(p, inv, i)) any = true;
                if (!any) return;
            }
            // From your pockets (inventory slot) into the container.
            case "put" -> {
                var pi = p.getInventory();
                if (slot < 0 || slot >= pi.main.size()) return;
                ItemStack s = pi.main.get(slot);
                if (s.isEmpty() || Satchel.isStory(s)) return;
                ItemStack rest = s.copy();
                for (int i = 0; i < inv.size() && !rest.isEmpty(); i++) {
                    ItemStack t = inv.getStack(i);
                    if (!t.isEmpty() && ItemStack.areItemsAndComponentsEqual(t, rest) && t.getCount() < t.getMaxCount()) {
                        int n = Math.min(rest.getCount(), t.getMaxCount() - t.getCount());
                        t.increment(n);
                        rest.decrement(n);
                    }
                }
                for (int i = 0; i < inv.size() && !rest.isEmpty(); i++) {
                    if (inv.getStack(i).isEmpty()) {
                        inv.setStack(i, rest);
                        seen.add(i);
                        rest = ItemStack.EMPTY;
                    }
                }
                if (rest.getCount() == s.getCount()) {
                    Notify.toast(p, Text.literal("It's full").formatted(Formatting.RED), null, 0xC0463A, "minecraft:barrel", null);
                    return;
                }
                pi.main.set(slot, rest);
                pi.markDirty();
                p.playSoundToPlayer(SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.6f, 0.9f);
            }
            default -> { return; }
        }
        inv.markDirty();
        refresh(p.getServer(), o);
    }

    /** Into your pockets: the loadout slot made for it if that's empty, else the backpack. */
    private static boolean take(ServerPlayerEntity p, Inventory inv, int slot) {
        ItemStack s = inv.getStack(slot);
        if (s.isEmpty()) return false;
        var pi = p.getInventory();
        ItemStack rest = s.copy();
        for (int j = 0; j < 9 && !rest.isEmpty(); j++) {
            if (Loadout.SLOTS[j] == Loadout.Kind.FREE || !pi.main.get(j).isEmpty() || !Loadout.fits(Loadout.SLOTS[j], rest)) continue;
            pi.main.set(j, rest);
            rest = ItemStack.EMPTY;
        }
        for (int j = 9; j < 36 && !rest.isEmpty(); j++) {
            ItemStack m = pi.main.get(j);
            if (!m.isEmpty() && ItemStack.areItemsAndComponentsEqual(m, rest) && m.getCount() < m.getMaxCount()) {
                int n = Math.min(rest.getCount(), m.getMaxCount() - m.getCount());
                m.increment(n);
                rest.decrement(n);
            }
        }
        for (int j = 9; j < 36 && !rest.isEmpty(); j++) {
            if (pi.main.get(j).isEmpty()) {
                pi.main.set(j, rest);
                rest = ItemStack.EMPTY;
            }
        }
        if (rest.getCount() == s.getCount()) {
            Notify.toast(p, Text.literal("Your pockets are full").formatted(Formatting.RED), null, 0xC0463A, "minecraft:bundle", null);
            return false;
        }
        inv.setStack(slot, rest);
        pi.markDirty();
        p.playSoundToPlayer(SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS, 0.6f, 1.2f);
        return true;
    }
}
