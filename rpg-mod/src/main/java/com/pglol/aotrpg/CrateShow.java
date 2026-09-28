package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.joml.Vector3f;

/**
 * A crate opening as everyone around sees it: a closed chest floats up over the opener while light
 * spirals round them and chimes climb, then it bursts open into the prize, named in its rarity's
 * colour, with a show that grows with the rarity. Legendary and Mythic pulls are called out in
 * chat to the whole server.
 */
public final class CrateShow {
    private CrateShow() {}

    public static final String TAG = "aot_crate_show";
    private static final int BUILD = 70, HOLD = 70;
    private static final int[] COLORS = {0xB8B8B8, 0x5BD35B, 0x4A8FE0, 0xB05AE0, 0xF2C14E, 0xE02A2A};
    private static final Formatting[] FORMATS = {Formatting.GRAY, Formatting.GREEN, Formatting.BLUE, Formatting.DARK_PURPLE, Formatting.GOLD, Formatting.RED};
    private static final String[] NAMES = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};

    /** The floating chests and prizes on show right now. */
    private static final java.util.Set<java.util.UUID> live = new java.util.HashSet<>();

    /** Leftover floating prizes (from before a restart) go; the ones on show now stay. */
    public static boolean stray(Entity e) {
        return e instanceof ItemEntity && e.getCommandTags().contains(TAG) && !live.contains(e.getUuid());
    }

    private static void gone(ItemEntity e) {
        if (e == null) return;
        live.remove(e.getUuid());
        e.discard();
    }

    public static void play(ServerPlayerEntity p, String crate, String desc, String iconId, ItemStack prize, int rarity) {
        ServerWorld w = p.getServerWorld();
        int rar = Math.max(0, Math.min(5, rarity));
        int col = COLORS[rar];
        Vector3f white = new Vector3f(1f, 1f, 1f), tone = new Vector3f(((col >> 16) & 0xFF) / 255f, ((col >> 8) & 0xFF) / 255f, (col & 0xFF) / 255f);
        ItemEntity chest = floating(w, p, new ItemStack(Items.CHEST), null);
        // The build: a spiral winding up round the opener, turning to the rarity's colour at the end.
        for (int t = 0; t < BUILD; t += 2) {
            int tt = t;
            AotRpg.SCHEDULER.later(t, () -> {
                if (p.isDisconnected()) return;
                float k = tt / (float) BUILD;
                Vector3f c = k > 0.7f ? tone : white;
                for (int arm = 0; arm < 2; arm++) {
                    double a = tt * 0.35 + arm * Math.PI, r = 1.3 - k * 0.6;
                    w.spawnParticles(new DustParticleEffect(c, 1.2f), p.getX() + Math.cos(a) * r, p.getY() + 0.2 + k * 2.4, p.getZ() + Math.sin(a) * r,
                        1, 0, 0, 0, 0);
                }
                w.spawnParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + 2.6, p.getZ(), 3, 0.4, 0.3, 0.4, 0.4);
                if (tt % 10 == 0) w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.7f, 0.6f + k * 1.2f);
            });
        }
        AotRpg.SCHEDULER.later(BUILD, () -> {
            gone(chest);
            if (p.isDisconnected()) return;
            reveal(w, p, rar, tone);
            ItemStack show = prize != null && !prize.isEmpty() ? prize.copy() : icon(iconId);
            ItemEntity shown = floating(w, p, show, Text.literal(desc).formatted(FORMATS[rar], rar >= 4 ? Formatting.BOLD : Formatting.RESET));
            if (shown != null) {
                if (rar >= 2) shown.setGlowing(true);
                AotRpg.SCHEDULER.later(HOLD, () -> gone(shown));
            }
            if (rar >= 4) announce(p, crate, desc, rar);
        });
    }

    private static ItemEntity floating(ServerWorld w, ServerPlayerEntity p, ItemStack s, Text name) {
        ItemEntity e = new ItemEntity(w, p.getX(), p.getY() + 2.7, p.getZ(), s, 0, 0, 0);
        e.setPickupDelayInfinite();
        e.setNoGravity(true);
        e.setVelocity(0, 0, 0);
        e.setNeverDespawn();
        e.addCommandTag(TAG);
        live.add(e.getUuid());
        if (name != null) {
            e.setCustomName(name);
            e.setCustomNameVisible(true);
        }
        return w.spawnEntity(e) ? e : null;
    }

    private static ItemStack icon(String id) {
        Identifier i = Identifier.tryParse(id == null ? "" : id);
        var item = i == null ? Items.AIR : Registries.ITEM.get(i);
        return new ItemStack(item == Items.AIR ? Items.NETHER_STAR : item);
    }

    /** The burst: bigger the rarer. */
    private static void reveal(ServerWorld w, ServerPlayerEntity p, int rar, Vector3f tone) {
        double x = p.getX(), y = p.getY() + 2.6, z = p.getZ();
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.PLAYERS, 0.9f, 1.1f);
        w.spawnParticles(new DustParticleEffect(tone, 1.6f), x, y, z, 30 + rar * 20, 0.6, 0.6, 0.6, 0.1);
        w.spawnParticles(ParticleTypes.END_ROD, x, y, z, 10 + rar * 10, 0.2, 0.2, 0.2, 0.15);
        if (rar >= 2) {
            w.spawnParticles(ParticleTypes.FIREWORK, x, y, z, 30 + rar * 15, 0.3, 0.3, 0.3, 0.25);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.8f, 1f);
        }
        if (rar >= 3) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
        if (rar >= 4) {
            w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 120, 0.5, 0.8, 0.5, 0.5);
            for (int k = 0; k < 20; k++) w.spawnParticles(new DustParticleEffect(tone, 2f), x, p.getY() + k * 0.6, z, 2, 0.1, 0.1, 0.1, 0);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.PLAYERS, 1f, 1f);
            w.playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1f);
        }
        if (rar == 5) {
            w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, x, p.getY() + 1, z, 80, 1.2, 1, 1.2, 0.05);
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, x, p.getY() + 1, z, 30, 1, 0.6, 1, 0.02);
            w.spawnParticles(ParticleTypes.EXPLOSION, x, y, z, 3, 0.5, 0.5, 0.5, 0);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.8f, 1.2f);
        }
    }

    /** Legendary and Mythic pulls, to the whole server. */
    private static void announce(ServerPlayerEntity p, String crate, String desc, int rar) {
        String who = AotRpg.PROFILES.get(p.getUuid()).name;
        Text msg = Text.literal(rar == 5 ? "✦ MYTHIC PULL ✦ " : "✦ ").formatted(FORMATS[rar], Formatting.BOLD)
            .append(Text.literal(who).formatted(Formatting.WHITE, Formatting.BOLD))
            .append(Text.literal(" pulled a ").formatted(Formatting.GRAY))
            .append(Text.literal(NAMES[rar].toUpperCase() + " " + desc).formatted(FORMATS[rar], Formatting.BOLD))
            .append(Text.literal(" from the " + crate + "!").formatted(Formatting.GRAY));
        for (ServerPlayerEntity o : p.getServer().getPlayerManager().getPlayerList()) {
            o.sendMessage(msg, false);
            if (rar == 5 && o != p) o.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.6f, 0.7f);
        }
    }
}
