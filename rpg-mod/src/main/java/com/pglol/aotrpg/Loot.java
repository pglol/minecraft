package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Personal loot. Everyone who fought a titan gets their own drops from it: only they can see
 * them, only they can pick them up. Nobody runs off with your legendary.
 *
 * Who counts: the killer, and anyone who hit the titan in the last 60 seconds and is still
 * within 96 blocks of it.
 */
public final class Loot {
    private static final String TAG = "aot_loot:";
    private static final long WINDOW_MS = 60_000;

    /** Titan -> (player -> when they last hit it). */
    private final Map<UUID, Map<UUID, Long>> hits = new HashMap<>();

    /** A player hit a titan. */
    public void hit(ServerPlayerEntity p, Entity titan) {
        hits.computeIfAbsent(titan.getUuid(), k -> new HashMap<>()).put(p.getUuid(), System.currentTimeMillis());
    }

    /** Who shares in a titan's loot (the killer first). Forgets the titan. */
    public List<ServerPlayerEntity> earners(ServerPlayerEntity killer, LivingEntity titan) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        if (killer != null) out.add(killer);
        Map<UUID, Long> m = hits.remove(titan.getUuid());
        if (m == null || killer == null) return out;
        long now = System.currentTimeMillis();
        for (var e : m.entrySet()) {
            if (now - e.getValue() > WINDOW_MS || e.getKey().equals(killer.getUuid())) continue;
            ServerPlayerEntity p = killer.getServer().getPlayerManager().getPlayer(e.getKey());
            if (p != null && p.getWorld() == titan.getWorld() && p.squaredDistanceTo(titan) < 96 * 96) out.add(p);
        }
        return out;
    }

    /** Every so often: forget titans nobody has hit for a while. */
    public void tick(int ticks) {
        if (ticks % 1200 != 0) return;
        long now = System.currentTimeMillis();
        hits.values().removeIf(m -> {
            m.values().removeIf(t -> now - t > WINDOW_MS);
            return m.isEmpty();
        });
    }

    /**
     * Kill loot, straight into your bag (nothing spilled on the ground to chase): the satchel in
     * the open world, your pockets on an extraction run (where it's yours only if you get out).
     */
    public static void claim(ServerPlayerEntity p, net.minecraft.item.ItemStack s) {
        if (s == null || s.isEmpty()) return;
        net.minecraft.item.ItemStack c = s.copy();
        // Read before handing over (a handed-over stack can come back empty).
        String name = c.getName().getString();
        int n = c.getCount();
        int rar = Gear.isGear(c) ? Gear.rarityOf(c) : -1;
        String icon = net.minecraft.registry.Registries.ITEM.getId(c.getItem()).toString();
        boolean run = Extraction.inRun(p.getUuid());
        if (run) p.getInventory().offerOrDrop(c);
        else AotRpg.SATCHEL.add(p, c);
        int[] cols = {0xDDDDDD, 0x55FF55, 0x5599FF, 0xC055FF, 0xFFB020, 0xFF2A2A};
        int col = rar >= 0 && rar < cols.length ? cols[rar] : 0xEDE3C8;
        Notify.toast(p, net.minecraft.text.Text.literal("+ " + name + (n > 1 ? "  ×" + n : "")).styled(x -> x.withColor(col)),
            net.minecraft.text.Text.literal(run ? "Into your pack" : "Into your satchel"), col, icon, "loot:" + name);
        p.playSoundToPlayer(net.minecraft.sound.SoundEvents.ENTITY_ITEM_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 0.35f, 1.3f + p.getRandom().nextFloat() * 0.3f);
    }

    /**
     * A mob just died: whatever it dropped this tick (its loot table, what it carried) goes to
     * whoever killed it instead of onto the ground.
     */
    public static void sweep(LivingEntity dead, ServerPlayerEntity killer) {
        if (!(dead.getWorld() instanceof net.minecraft.server.world.ServerWorld w)) return;
        for (ItemEntity e : w.getEntitiesByClass(ItemEntity.class, dead.getBoundingBox().expand(2, 3, 2), x -> x.isAlive() && x.age <= 1 && owner(x) == null)) {
            claim(killer, e.getStack());
            e.discard();
        }
    }

    /** Makes a dropped item this player's alone: nobody else sees it or picks it up. */
    public static void own(ItemEntity e, ServerPlayerEntity p) {
        e.setOwner(p.getUuid());
        e.addCommandTag(TAG + p.getUuidAsString());
    }

    /** The player a dropped item belongs to, or null if it's anyone's. */
    public static UUID owner(Entity e) {
        if (!(e instanceof ItemEntity)) return null;
        for (String t : e.getCommandTags()) {
            if (t.startsWith(TAG)) {
                try {
                    return UUID.fromString(t.substring(TAG.length()));
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /** Someone else's loot: not sent to this player at all. */
    public static boolean hiddenFrom(Entity e, ServerPlayerEntity p) {
        UUID o = owner(e);
        return o != null && !o.equals(p.getUuid());
    }
}
