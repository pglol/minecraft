package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Box;
import org.joml.Vector3f;

/**
 * Good gear lying on the ground shows from afar: a column of light in its rarity's colour rising
 * from it (taller and brighter the rarer it is, with sparks for a Mythic), so a fallen troop's
 * drop or a hoard bursting from an abnormal reads at a glance.
 */
public final class LootBeams {
    private LootBeams() {}

    private static final int[] COLOR = {0, 0, 0x3A7AFF, 0xB04AFF, 0xFFB020, 0xFF2A2A};

    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.isPaused() || mc.world.getTime() % 3 != 0) return;
        Box around = mc.player.getBoundingBox().expand(56, 24, 56);
        for (ItemEntity e : mc.world.getEntitiesByClass(ItemEntity.class, around, ie -> com.pglol.aotrpg.Gear.isGear(ie.getStack()))) {
            int r = GearUi.rarity(e.getStack());
            if (r < 2 || r >= COLOR.length) continue;
            int c = COLOR[r];
            Vector3f rgb = new Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
            double tall = r >= 5 ? 6 : r == 4 ? 4.5 : r == 3 ? 3 : 2;
            var rnd = mc.world.random;
            for (int k = 0; k < (r >= 4 ? 4 : 2); k++) {
                double y = e.getY() + 0.3 + rnd.nextDouble() * tall;
                mc.world.addParticle(new DustParticleEffect(rgb, r >= 4 ? 1.1f : 0.8f), true, e.getX(), y, e.getZ(), 0, 0.02, 0);
            }
            if (r >= 5 && rnd.nextInt(3) == 0) mc.world.addParticle(ParticleTypes.END_ROD, true, e.getX(), e.getY() + tall, e.getZ(),
                (rnd.nextDouble() - 0.5) * 0.05, 0.02, (rnd.nextDouble() - 0.5) * 0.05);
        }
    }
}
