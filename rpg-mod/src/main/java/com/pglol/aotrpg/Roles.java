package com.pglol.aotrpg;

import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Roleplay participation. Players who opt in (/rp on) earn rank by playing their character:
 * a point a minute while active, more for quests and work orders. Rank gives buffs only while
 * they keep participating (more XP and Marks), and shows as a tag by their name on name
 * plates, in chat and in the player list. Operators can give anyone a custom role title.
 */
public final class Roles {
    public record Rank(String title, String abbr, int points, int color) { }

    public static final Rank[] RANKS = {
        new Rank("Recruit", "RCT", 0, 0x9A9486),
        new Rank("Cadet", "CDT", 60, 0xC8BFA8),
        new Rank("Private", "PVT", 300, 0x9FD8A0),
        new Rank("Corporal", "CPL", 900, 0x6FC8E8),
        new Rank("Sergeant", "SGT", 2000, 0x5A9FFF),
        new Rank("Lieutenant", "LT", 4000, 0xC07AFF),
        new Rank("Captain", "CPT", 8000, 0xFF9A3A),
        new Rank("Commander", "CMD", 15000, 0xFFD24A)};

    private static final Identifier HEADING = Identifier.of("aot_rpg", "heading");

    private final Map<UUID, double[]> lastSeen = new HashMap<>();
    private final Map<UUID, Long> movedAt = new HashMap<>();

    public static int tier(Profile pr) {
        int t = 0;
        for (int i = 0; i < RANKS.length; i++) if (pr.rpPoints >= RANKS[i].points()) t = i;
        return t;
    }

    public static Rank rank(Profile pr) {
        return RANKS[tier(pr)];
    }

    /** Bonus fraction for XP and Marks earned while participating (+3% per rank). */
    public static double bonus(Profile pr) {
        return pr.rp ? 0.03 * tier(pr) : 0;
    }

    /** The tag shown by the name: an operator role, or the RP rank, or nothing. */
    public static String tag(Profile pr) {
        if (pr.role != null && !pr.role.isEmpty()) return pr.role;
        if (pr.rp) return rank(pr).title();
        Tasks.Achievement a = Tasks.worn(pr);
        return a == null ? "" : a.title();
    }

    public static int tagColor(Profile pr) {
        if (pr.role != null && !pr.role.isEmpty()) return pr.roleColor == 0 ? 0xE04A3A : pr.roleColor;
        if (!pr.rp && Tasks.worn(pr) != null) return Tasks.worn(pr).color();
        return rank(pr).color();
    }

    /** "[RP · Sergeant] Name" for chat and the player list. */
    /** The styled name with the player's regiment tag in front ("[TAG] ..."). */
    public static MutableText styledName(Profile pr, java.util.UUID id) {
        MutableText n = styledName(pr);
        Regiments.Regiment r = AotRpg.REGIMENTS.of(id);
        if (r == null) return n;
        return Text.literal("[" + r.tag + "] ").setStyle(Style.EMPTY.withColor(r.color).withBold(true)).append(n);
    }

    public static MutableText styledName(Profile pr) {
        MutableText name = Text.literal(pr.name).setStyle(Style.EMPTY.withColor(0xEDE3C8));
        Factions.Faction fa = Factions.of(pr);
        if (fa != null) name = Text.literal(Factions.abbr(fa) + " ").setStyle(Style.EMPTY.withColor(fa.color).withBold(true)).append(name);
        String t = tag(pr);
        if (t.isEmpty()) return name;
        MutableText tag = Text.literal((pr.role != null && !pr.role.isEmpty() || !pr.rp ? "" : "RP · ") + t)
            .setStyle(Style.EMPTY.withColor(tagColor(pr)).withFont(HEADING));
        return Text.literal("[").formatted(Formatting.DARK_GRAY).append(tag).append(Text.literal("] ").formatted(Formatting.DARK_GRAY)).append(name);
    }

    public void toggle(ServerPlayerEntity p, boolean on) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        pr.rp = on;
        AotRpg.PROFILES.save(p.getUuid());
        p.sendMessage(on ? Text.literal("Roleplay on: you earn rank and its bonuses while you play your character.").formatted(Formatting.GOLD)
            : Text.literal("Roleplay off: rank is kept, bonuses pause.").formatted(Formatting.GRAY), false);
        refresh(p);
    }

    public void setRole(ServerPlayerEntity p, String role, int color) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        pr.role = role;
        pr.roleColor = color;
        AotRpg.PROFILES.save(p.getUuid());
        refresh(p);
    }

    public void addPoints(ServerPlayerEntity p, int n) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.rp || n <= 0) return;
        int before = tier(pr);
        pr.rpPoints += n;
        if (tier(pr) > before) {
            Rank r = rank(pr);
            Titles.show(p, Text.literal("PROMOTED").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(r.title() + "  ·  +" + Math.round(bonus(pr) * 100) + "% XP and Marks").withColor(r.color()), 10, 60, 20);
            p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1f);
            refresh(p);
        }
        AotRpg.PROFILES.save(p.getUuid());
    }

    /** A point a minute for active roleplayers (standing still for five minutes counts as away). */
    public void tick(ServerPlayerEntity p, int ticks) {
        if (ticks % 100 != 0) return;
        double[] last = lastSeen.get(p.getUuid());
        double[] now = {p.getX(), p.getY(), p.getZ(), p.getYaw()};
        if (last == null || Math.abs(last[0] - now[0]) + Math.abs(last[1] - now[1]) + Math.abs(last[2] - now[2]) + Math.abs(last[3] - now[3]) > 0.5) {
            movedAt.put(p.getUuid(), (long) ticks);
        }
        lastSeen.put(p.getUuid(), now);
        if (ticks % 1200 != 0) return;
        long moved = movedAt.getOrDefault(p.getUuid(), (long) ticks);
        if (ticks - moved < 20 * 300) addPoints(p, 1);
    }

    /** Pushes the new tag to name plates and the player list. */
    public void refresh(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        AotRpg.NAMETAGS.update(p, pr);
        var pkt = new PlayerListS2CPacket(PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME, p);
        for (ServerPlayerEntity o : p.getServer().getPlayerManager().getPlayerList()) o.networkHandler.sendPacket(pkt);
        AotRpg.sync(p, pr);
    }

    public void forget(ServerPlayerEntity p) {
        lastSeen.remove(p.getUuid());
        movedAt.remove(p.getUuid());
    }
}
