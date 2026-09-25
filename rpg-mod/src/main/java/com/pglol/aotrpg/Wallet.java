package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Money. Marks are the in-game currency each character earns and spends (quests, markets, homes).
 * Gold is account wide and reserved for cosmetics and the battle pass (granted by operators until
 * purchasing is added).
 */
public final class Wallet {
    public static MutableText marks(long n) {
        return Text.literal(String.format(java.util.Locale.ROOT, "%,d", n) + " Marks").formatted(Formatting.GOLD);
    }

    public static MutableText gold(long n) {
        return Text.literal(String.format(java.util.Locale.ROOT, "%,d", n) + " Gold").formatted(Formatting.YELLOW);
    }

    public long marks(ServerPlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid()).marks;
    }

    public void addMarks(ServerPlayerEntity p, long n, String why) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        pr.marks = Math.max(0, pr.marks + n);
        AotRpg.PROFILES.save(p.getUuid());
        sync(p);
        if (why != null && n != 0) {
            Notify.toast(p, Text.literal(n > 0 ? "+ " : "- ").formatted(n > 0 ? Formatting.GREEN : Formatting.RED).append(marks(Math.abs(n))),
                Text.literal(why), 0xE0B96A, "minecraft:gold_nugget", null);
        }
    }

    /** Marks earned by playing (quests, bounties, orders): roleplay rank adds its bonus. */
    public void earn(ServerPlayerEntity p, long n, String why) {
        double b = Roles.bonus(AotRpg.PROFILES.get(p.getUuid()));
        long got = Math.round(n * (1 + b));
        addMarks(p, got, why);
        AotRpg.TASKS.count(p, Tasks.MARKS, got);
    }

    /** Takes Marks if the character has enough. */
    public boolean spendMarks(ServerPlayerEntity p, long n) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (n < 0 || pr.marks < n) return false;
        pr.marks -= n;
        AotRpg.PROFILES.save(p.getUuid());
        sync(p);
        return true;
    }

    public long gold(ServerPlayerEntity p) {
        return AotRpg.PROFILES.account(p.getUuid()).gold;
    }

    public void addGold(ServerPlayerEntity p, long n) {
        ProfileStore.Account a = AotRpg.PROFILES.account(p.getUuid());
        a.gold = Math.max(0, a.gold + n);
        AotRpg.PROFILES.saveAccount(p.getUuid());
        sync(p);
    }

    public boolean spendGold(ServerPlayerEntity p, long n) {
        ProfileStore.Account a = AotRpg.PROFILES.account(p.getUuid());
        if (n < 0 || a.gold < n) return false;
        a.gold -= n;
        AotRpg.PROFILES.saveAccount(p.getUuid());
        sync(p);
        return true;
    }

    public void sync(ServerPlayerEntity p) {
        if (ServerPlayNetworking.canSend(p, Net.WalletSync.ID)) ServerPlayNetworking.send(p, new Net.WalletSync(marks(p), gold(p)));
    }
}
