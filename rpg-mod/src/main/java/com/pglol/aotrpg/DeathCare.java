package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Death rules. Story mode (default): gear and inventory are kept, worn gear loses 10% durability
 * and you lose 10% of your current level's XP. Extraction mode: items drop as usual.
 * The satchel is never lost in any mode.
 */
public final class DeathCare {
    public static final String STORY = "story", EXTRACTION = "extraction";

    public static boolean keepsItems(ServerPlayerEntity p) {
        return !EXTRACTION.equals(AotRpg.PROFILES.get(p.getUuid()).mode);
    }

    public void onDeath(ServerPlayerEntity p, DamageSource source) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        boolean story = keepsItems(p);
        long lost = 0;
        if (pr.created && pr.level < Progression.MAX_LEVEL) {
            lost = pr.xp / 10;
            pr.xp -= lost;
        }
        int worn = 0;
        if (story) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack s = p.getEquippedStack(slot);
                if (!s.isDamageable()) continue;
                int dmg = Math.max(1, s.getMaxDamage() / 10);
                s.setDamage(Math.min(s.getMaxDamage() - 1, s.getDamage() + dmg));
                worn++;
            }
        }
        AotRpg.PROFILES.save(p.getUuid());
        if (ServerPlayNetworking.canSend(p, Net.DeathInfo.ID)) {
            String killer = source.getAttacker() != null ? source.getAttacker().getName().getString() : "";
            ServerPlayNetworking.send(p, new Net.DeathInfo(source.getDeathMessage(p).getString(), killer,
                story ? STORY : EXTRACTION, lost, worn));
        }
    }
}
