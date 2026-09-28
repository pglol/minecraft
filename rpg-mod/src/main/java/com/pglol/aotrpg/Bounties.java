package com.pglol.aotrpg;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wanted. Wear stolen gear where townspeople can see it, or kill too many other players, and you
 * may be reported: a price goes on your head (8,000 to 15,000 Marks, climbing as the clock runs
 * down) and every player on the server hears of it, with a rough idea of where you were a minute
 * ago. It lasts 10 to 20 minutes. Log out and you slip away for now, but the clock only runs while
 * you're here: come back and the hunt picks up where it left off. Whoever kills you claims it.
 */
public final class Bounties {
    private static final long MIN_MS = 10 * 60_000, MAX_MS = 20 * 60_000;
    private static final int PK_LIMIT = 3;
    private static final long PK_WINDOW = 30 * 60_000;

    private final Map<UUID, ArrayDeque<Long>> kills = new HashMap<>();
    /** Where each wanted player was last seen (roughly), refreshed each minute. */
    private final Map<UUID, int[]> seen = new HashMap<>();
    private MinecraftServer server;
    private long lastTick;

    public void open(MinecraftServer server) {
        this.server = server;
        lastTick = System.currentTimeMillis();
    }

    private static Profile pr(ServerPlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid());
    }

    public boolean wanted(ServerPlayerEntity p) {
        return pr(p).huntLeft > 0;
    }

    /** The reward right now: 8,000 at the start up to 15,000 as time runs out. */
    public long reward(Profile pr) {
        if (pr.huntTotal <= 0) return 0;
        double gone = 1 - Math.max(0, Math.min(1, pr.huntLeft / (double) pr.huntTotal));
        return Math.round((8000 + 7000 * gone) / 100.0) * 100;
    }

    private static String name(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        return pr.created && pr.name != null && !pr.name.isBlank() ? pr.name : p.getName().getString();
    }

    // ------------------------------------------------------------------ being reported

    /** Starts a hunt for this player (if there isn't one already). */
    public void report(ServerPlayerEntity p, String why) {
        Profile pr = pr(p);
        if (pr.huntLeft > 0 || Duels.isDueling(p)) return;
        long ms = MIN_MS + p.getRandom().nextInt((int) (MAX_MS - MIN_MS));
        if (pr.has(Skill.LOW_PROFILE)) ms = Math.max(5 * 60_000, ms - 3 * 60_000);
        pr.huntLeft = ms;
        pr.huntTotal = ms;
        pr.huntWhy = why;
        AotRpg.PROFILES.save(p.getUuid());
        spot(p);
        Titles.show(p, Text.literal("WANTED").formatted(Formatting.DARK_RED, Formatting.BOLD), Text.literal(why + " · survive " + (ms / 60_000) + " minutes"), 5, 60, 15);
        p.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 0.6f, 1.2f);
        announce(p, true);
    }

    /** Tells every other player about a hunt (or one player, when they join mid-hunt). */
    private void announce(ServerPlayerEntity target, boolean everyone) {
        for (ServerPlayerEntity o : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            if (o == target) continue;
            tell(o, target);
        }
    }

    private void tell(ServerPlayerEntity o, ServerPlayerEntity target) {
        Profile pr = pr(target);
        int[] at = seen.get(target.getUuid());
        String where = at == null ? "" : " · last seen near " + at[0] + ", " + at[1];
        Notify.toast(o, Text.literal("WANTED: " + name(target)).formatted(Formatting.RED, Formatting.BOLD),
            Text.literal(String.format("%,d Marks", reward(pr)) + where), 0xC0463A, "minecraft:skeleton_skull", "bounty");
    }

    /** A rough fix on where they are: within a couple of hundred blocks, not exact. */
    private void spot(ServerPlayerEntity p) {
        var r = p.getRandom();
        int x = (int) (p.getX() + (r.nextDouble() - 0.5) * 300), z = (int) (p.getZ() + (r.nextDouble() - 0.5) * 300);
        seen.put(p.getUuid(), new int[] {Math.round(x / 50f) * 50, Math.round(z / 50f) * 50, (int) p.getY()});
    }

    // ------------------------------------------------------------------ the clock

    public void tick(int ticks) {
        if (server == null) return;
        long now = System.currentTimeMillis(), dt = Math.max(0, Math.min(5000, now - lastTick));
        lastTick = now;
        for (ServerPlayerEntity p : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            Profile pr = pr(p);
            if (!pr.created) continue;
            if (pr.huntLeft > 0) {
                pr.huntLeft -= dt;
                if (pr.huntLeft <= 0) {
                    pr.huntLeft = 0;
                    pr.huntTotal = 0;
                    seen.remove(p.getUuid());
                    AotRpg.PROFILES.save(p.getUuid());
                    Notify.toast(p, Text.literal("The hunt is over").formatted(Formatting.GREEN, Formatting.BOLD), Text.literal("Nobody claimed your bounty"), 0x5BD35B, null, "bounty");
                    for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
                        if (o != p) Notify.toast(o, Text.literal(name(p) + " got away").formatted(Formatting.GRAY), Text.literal("The bounty has lapsed"), 0x8F8A7A, null, "bounty");
                    }
                } else if (ticks % 1200 == 0) {
                    // Once a minute: a new rough sighting for the hunters, and the price goes up.
                    spot(p);
                    for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) if (o != p) AotRpg.QUESTS.markers(o, true);
                    if ((pr.huntLeft / 60_000) % 3 == 0) announce(p, true);
                    p.sendMessage(Text.literal("Wanted · " + (pr.huntLeft / 60_000 + 1) + " min left · " + String.format("%,d", reward(pr)) + " Marks on your head")
                        .formatted(Formatting.RED), true);
                }
            }
            // Every half minute in town: anyone seen with stolen gear may be reported.
            if (ticks % 600 == 300 && pr.huntLeft <= 0) checkStolen(p, pr);
        }
    }

    private void checkStolen(ServerPlayerEntity p, Profile pr) {
        if (AotRpg.PLACES.nearest(p.getX(), p.getZ(), 120, "town", "village", "city", "capital", "safe") == null) return;
        boolean showing = false;
        for (ItemStack s : new ItemStack[] {p.getMainHandStack(), p.getOffHandStack(),
            p.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD), p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST),
            p.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS), p.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET)}) {
            if (Vendors.isStolen(s)) showing = true;
        }
        if (!showing) return;
        List<VillagerEntity> eyes = p.getServerWorld().getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(14),
            v -> Townsfolk.folk(v) && !v.isBaby() && !v.isSleeping() && v.canSee(p));
        if (eyes.isEmpty()) return;
        double chance = 0.22 * (pr.has(Skill.LOW_PROFILE) ? 0.5 : 1);
        if (p.getRandom().nextDouble() >= chance) return;
        VillagerEntity snitch = eyes.get(p.getRandom().nextInt(eyes.size()));
        Townsfolk.face(snitch, p.getX(), p.getZ());
        p.sendMessage(Text.literal("\"That's stolen! Guards! GUARDS!\"").formatted(Formatting.RED, Formatting.ITALIC), false);
        report(p, "Seen with stolen gear");
    }

    // ------------------------------------------------------------------ comings and goings

    public void joined(ServerPlayerEntity p) {
        // Back on: the hunt resumes, and everyone hears it.
        if (pr(p).huntLeft > 0) {
            spot(p);
            Notify.toast(p, Text.literal("You're still wanted").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal((pr(p).huntLeft / 60_000 + 1) + " minutes to go"), 0xC0463A, "minecraft:skeleton_skull", "bounty");
            announce(p, true);
        }
        // And someone joining hears of every hunt going on.
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) if (o != p && pr(o).huntLeft > 0) tell(p, o);
    }

    public void left(ServerPlayerEntity p) {
        if (pr(p).huntLeft <= 0) return;
        seen.remove(p.getUuid());
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (o != p) Notify.toast(o, Text.literal(name(p) + " slipped away").formatted(Formatting.GRAY), Text.literal("For now"), 0x8F8A7A, null, "bounty");
        }
    }

    /** A player killed another (outside a duel): claims a bounty, or counts toward one of their own. */
    public void playerKilled(ServerPlayerEntity dead, ServerPlayerEntity killer) {
        if (killer == null || killer == dead) return;
        Profile pd = pr(dead);
        if (pd.huntLeft > 0) {
            long r = reward(pd);
            pd.huntLeft = 0;
            pd.huntTotal = 0;
            seen.remove(dead.getUuid());
            AotRpg.PROFILES.save(dead.getUuid());
            AotRpg.WALLET.addMarks(killer, r, "bounty on " + name(dead));
            Titles.show(killer, Text.literal("BOUNTY CLAIMED").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal(String.format("+%,d Marks", r)), 5, 50, 15);
            for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
                if (o != killer) Notify.toast(o, Text.literal(name(killer) + " claimed the bounty").formatted(Formatting.GOLD),
                    Text.literal("on " + name(dead)), 0xE0B96A, "minecraft:skeleton_skull", "bounty");
            }
            return;
        }
        // Killing the wanted is a service; killing anyone else too often is a crime.
        long now = System.currentTimeMillis();
        ArrayDeque<Long> q = kills.computeIfAbsent(killer.getUuid(), k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > PK_WINDOW) q.removeFirst();
        if (q.size() >= PK_LIMIT) {
            q.clear();
            report(killer, "Too many players killed");
        }
    }

    /** Hunters see a rough marker for each wanted player. */
    public void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        for (var e : seen.entrySet()) {
            if (e.getKey().equals(p.getUuid())) continue;
            ServerPlayerEntity t = server.getPlayerManager().getPlayer(e.getKey());
            if (t == null || pr(t).huntLeft <= 0) continue;
            int[] a = e.getValue();
            list.add(new Net.Marker("event", "Wanted: " + name(t) + " (around here)", a[0], a[2], a[1], 0xE04A3A));
        }
    }

    /** A line of gossip about whoever's wanted. */
    public String rumour(ServerPlayerEntity p) {
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (o != p && pr(o).huntLeft > 0) return "Have you heard? There's " + String.format("%,d", reward(pr(o))) + " Marks on " + name(o) + "'s head. Someone saw them near " + (seen.containsKey(o.getUuid()) ? seen.get(o.getUuid())[0] + ", " + seen.get(o.getUuid())[1] : "the edge of town") + ".";
        }
        return null;
    }
}
