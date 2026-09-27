package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Duels: challenge another player (/duel name), they accept, and an intro shows you both off, VS
 * card and all, with anyone nearby watching. A three-count, then fight. The blow that would kill
 * ends it instead: nobody dies or loses anything, the winner's kill effect plays over the loser.
 * Three minutes and it's a draw; walking off (or logging out) forfeits.
 */
public final class Duels {
    private static final long CHALLENGE_MS = 30_000, LIMIT_MS = 180_000;
    private static final double RANGE = 40;

    private static final class Duel {
        UUID a, b;
        long startsAt, endsAt;
        boolean fighting;
        Vec3d center;
    }

    /** Challenges: target -> (challenger, when). */
    private final Map<UUID, UUID> challengedBy = new HashMap<>();
    private final Map<UUID, Long> challengedAt = new HashMap<>();
    private final List<Duel> duels = new ArrayList<>();
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        duels.clear();
        challengedBy.clear();
    }

    private Duel of(UUID id) {
        for (Duel d : duels) if (d.a.equals(id) || d.b.equals(id)) return d;
        return null;
    }

    /** Both in the same duel. */
    public boolean dueling(UUID x, UUID y) {
        Duel d = of(x);
        return d != null && (d.a.equals(y) || d.b.equals(y));
    }

    public void challenge(ServerPlayerEntity p, ServerPlayerEntity t) {
        if (p == t) return;
        if (of(p.getUuid()) != null || of(t.getUuid()) != null) {
            Notify.toast(p, Text.literal("Someone's already in a duel").formatted(Formatting.RED), null, 0xC0463A, null, null);
            return;
        }
        if (p.getServerWorld() != t.getServerWorld() || p.squaredDistanceTo(t) > RANGE * RANGE) {
            Notify.toast(p, Text.literal("Get closer to " + t.getName().getString()).formatted(Formatting.RED), Text.literal("Within " + (int) RANGE + " blocks"), 0xC0463A, null, null);
            return;
        }
        challengedBy.put(t.getUuid(), p.getUuid());
        challengedAt.put(t.getUuid(), System.currentTimeMillis());
        String name = AotRpg.PROFILES.get(p.getUuid()).name;
        Notify.toast(p, Text.literal("Challenge sent").formatted(Formatting.GOLD), Text.literal("To " + AotRpg.PROFILES.get(t.getUuid()).name), 0xE0B96A, "minecraft:iron_sword", "duel");
        Notify.toast(t, Text.literal(name + " challenges you to a duel").formatted(Formatting.GOLD), Text.literal("/duel accept · 30 seconds to answer"), 0xE0B96A,
            "minecraft:iron_sword", "duel");
        t.sendMessage(Text.literal("[Accept duel]").formatted(Formatting.GOLD, Formatting.BOLD).styled(s -> s
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/duel accept"))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Fight " + name)))), false);
        t.playSoundToPlayer(SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.PLAYERS, 1f, 0.8f);
    }

    public void accept(ServerPlayerEntity t) {
        UUID from = challengedBy.remove(t.getUuid());
        Long at = challengedAt.remove(t.getUuid());
        ServerPlayerEntity p = from == null ? null : server.getPlayerManager().getPlayer(from);
        if (p == null || at == null || System.currentTimeMillis() - at > CHALLENGE_MS) {
            Notify.toast(t, Text.literal("No challenge to accept").formatted(Formatting.GRAY), null, 0x8F8A7A, null, null);
            return;
        }
        if (of(p.getUuid()) != null || of(t.getUuid()) != null || p.getServerWorld() != t.getServerWorld() || p.squaredDistanceTo(t) > RANGE * RANGE) {
            Notify.toast(t, Text.literal("The duel can't start").formatted(Formatting.RED), Text.literal("Too far apart, or someone's busy"), 0xC0463A, null, null);
            return;
        }
        Duel d = new Duel();
        d.a = p.getUuid();
        d.b = t.getUuid();
        d.center = p.getPos().add(t.getPos()).multiply(0.5);
        List<ServerPlayerEntity> viewers = Cinematics.near(p.getServerWorld(), d.center, 32);
        if (!viewers.contains(p)) viewers.add(0, p);
        if (!viewers.contains(t)) viewers.add(t);
        String an = AotRpg.PROFILES.get(p.getUuid()).name, bn = AotRpg.PROFILES.get(t.getUuid()).name;
        int ticks = Cinematics.intro(viewers, d.center, List.of(p, t), "DUEL", an + "   vs   " + bn, 0xE0B96A);
        long now = System.currentTimeMillis();
        d.startsAt = now + ticks * 50L + 3_000;
        d.endsAt = d.startsAt + LIMIT_MS;
        duels.add(d);
        // Three, two, one...
        for (int i = 3; i >= 1; i--) {
            int n = i;
            AotRpg.SCHEDULER.later(ticks + (3 - i) * 20, () -> count(d, Text.literal(String.valueOf(n)).formatted(Formatting.GOLD, Formatting.BOLD), 1f));
        }
        AotRpg.SCHEDULER.later(ticks + 60, () -> {
            d.fighting = true;
            count(d, Text.literal("FIGHT!").formatted(Formatting.RED, Formatting.BOLD), 1.6f);
        });
    }

    private void count(Duel d, Text t, float pitch) {
        if (!duels.contains(d)) return;
        for (UUID id : List.of(d.a, d.b)) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null) continue;
            Titles.show(p, t, Text.empty(), 0, 16, 4);
            p.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.MASTER, 1f, pitch);
        }
    }

    /** Before a duelist would die: the duel ends there instead. False keeps them alive. */
    public boolean allowDeath(ServerPlayerEntity p) {
        Duel d = of(p.getUuid());
        if (d == null) return true;
        p.setHealth(Math.max(2f, p.getMaxHealth() * 0.25f));
        UUID winner = d.a.equals(p.getUuid()) ? d.b : d.a;
        finish(d, winner);
        return false;
    }

    /** No hits before the count is over. */
    public boolean allowHit(ServerPlayerEntity victim, ServerPlayerEntity attacker) {
        Duel d = of(victim.getUuid());
        if (d == null || !(d.a.equals(attacker.getUuid()) || d.b.equals(attacker.getUuid()))) return true;
        return d.fighting;
    }

    public void tick(int ticks) {
        if (ticks % 10 != 0 || duels.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Duel d : new ArrayList<>(duels)) {
            ServerPlayerEntity a = server.getPlayerManager().getPlayer(d.a), b = server.getPlayerManager().getPlayer(d.b);
            if (a == null || b == null) {
                finish(d, a == null ? d.b : d.a);
                continue;
            }
            if (a.squaredDistanceTo(d.center) > 64 * 64 || a.getServerWorld() != b.getServerWorld()) finish(d, d.b);
            else if (b.squaredDistanceTo(d.center) > 64 * 64) finish(d, d.a);
            else if (now > d.endsAt) finish(d, null);
        }
    }

    /** Ends a duel: winner (null for a draw). */
    private void finish(Duel d, UUID winner) {
        if (!duels.remove(d)) return;
        ServerPlayerEntity a = server.getPlayerManager().getPlayer(d.a), b = server.getPlayerManager().getPlayer(d.b);
        if (winner == null) {
            for (ServerPlayerEntity p : new ServerPlayerEntity[] {a, b}) {
                if (p != null) Notify.toast(p, Text.literal("DRAW").formatted(Formatting.GRAY, Formatting.BOLD), Text.literal("Time's up"), 0x8F8A7A, "minecraft:clock", "duel");
            }
            return;
        }
        ServerPlayerEntity w = winner.equals(d.a) ? a : b, l = winner.equals(d.a) ? b : a;
        String wn = AotRpg.PROFILES.get(winner).name;
        if (w != null) {
            Titles.show(w, Text.literal("VICTORY").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal("You beat " + (l == null ? "them" : AotRpg.PROFILES.get(l.getUuid()).name)), 5, 50, 15);
            w.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.8f, 1f);
            AotRpg.TASKS.count(w, "duels_won", 1);
        }
        if (l != null) {
            Titles.show(l, Text.literal("DEFEAT").formatted(Formatting.RED, Formatting.BOLD), Text.literal(wn + " wins the duel"), 5, 50, 15);
            if (w != null) {
                // The winner's kill effect, over the loser, for everyone around.
                Vec3d c = l.getBoundingBox().getCenter();
                Net.SlashFx fx = new Net.SlashFx(AotRpg.COSMETICS.selected(w, "kill"), c.x, c.y, c.z, l.getHeight());
                for (ServerPlayerEntity o : Cinematics.near(l.getServerWorld(), c, 96)) {
                    if (ServerPlayNetworking.canSend(o, Net.SlashFx.ID)) ServerPlayNetworking.send(o, fx);
                }
            }
        }
    }
}
