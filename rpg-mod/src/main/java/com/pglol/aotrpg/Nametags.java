package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shows each player's character name (and level/discipline) floating above their head,
 * using a text display riding the player. The vanilla username tag is hidden with a team.
 */
public final class Nametags {
    public static final String TAG = "aot_nametag";
    private static final String TEAM = "aot_players";
    private final Map<UUID, DisplayEntity.TextDisplayEntity> tags = new HashMap<>();
    private final Map<UUID, String> shown = new HashMap<>();

    private Team team(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        Team t = sb.getTeam(TEAM);
        if (t == null) {
            t = sb.addTeam(TEAM);
            t.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.NEVER);
            t.setCollisionRule(AbstractTeam.CollisionRule.ALWAYS);
            t.setFriendlyFireAllowed(true);
        }
        return t;
    }

    private static Text label(Profile pr) {
        Formatting c = switch (pr.discipline) {
            case SCOUT -> Formatting.GREEN;
            case VANGUARD -> Formatting.RED;
            case GUARDIAN -> Formatting.BLUE;
            case MARKSMAN -> Formatting.YELLOW;
            case MEDIC -> Formatting.LIGHT_PURPLE;
        };
        return Text.literal(pr.name).formatted(Formatting.WHITE, Formatting.BOLD)
            .append(Text.literal("\n"))
            .append(Text.literal("Lv " + pr.level + " ").formatted(Formatting.GOLD))
            .append(Text.literal(pr.discipline.title).formatted(c));
    }

    /** Creates or refreshes the tag. Cheap to call often. */
    public void update(ServerPlayerEntity p, Profile pr) {
        if (!pr.created || p.isDisconnected()) return;
        Team t = team(p.getServer());
        if (p.getScoreboardTeam() != t) p.getServer().getScoreboard().addScoreHolderToTeam(p.getNameForScoreboard(), t);

        DisplayEntity.TextDisplayEntity d = tags.get(p.getUuid());
        if (d == null || d.isRemoved() || d.getWorld() != p.getWorld() || d.getVehicle() != p) {
            if (d != null) d.discard();
            d = spawn(p, pr);
            if (d == null) return;
            tags.put(p.getUuid(), d);
        } else if (!label(pr).getString().equals(shown.get(p.getUuid()))) {
            write(d, pr, p.getServerWorld());
        }
        shown.put(p.getUuid(), label(pr).getString());
    }

    private DisplayEntity.TextDisplayEntity spawn(ServerPlayerEntity p, Profile pr) {
        ServerWorld w = p.getServerWorld();
        DisplayEntity.TextDisplayEntity d = EntityType.TEXT_DISPLAY.create(w);
        if (d == null) return null;
        write(d, pr, w);
        d.addCommandTag(TAG);
        d.refreshPositionAndAngles(p.getX(), p.getY() + p.getHeight(), p.getZ(), 0, 0);
        // Register before spawning: the load event must not mistake it for a stray tag.
        tags.put(p.getUuid(), d);
        w.spawnEntity(d);
        d.startRiding(p, true);
        return d;
    }

    private static void write(DisplayEntity.TextDisplayEntity d, Profile pr, ServerWorld w) {
        // Round-trip through NBT: the display setters are private, readNbt is public.
        NbtCompound n = d.writeNbt(new NbtCompound());
        n.putString("text", Text.Serialization.toJsonString(label(pr), w.getRegistryManager()));
        n.putString("billboard", "center");
        n.putFloat("view_range", 0.35f); // about 22 blocks: a name you see up close
        n.putInt("background", 0x40000000);
        n.putBoolean("shadow", true);
        NbtCompound tf = new NbtCompound();
        tf.put("translation", floats(0f, 0.35f, 0f));
        tf.put("left_rotation", floats(0f, 0f, 0f, 1f));
        tf.put("right_rotation", floats(0f, 0f, 0f, 1f));
        tf.put("scale", floats(0.8f, 0.8f, 0.8f));
        n.put("transformation", tf);
        d.readNbt(n);
    }

    private static net.minecraft.nbt.NbtList floats(float... v) {
        net.minecraft.nbt.NbtList l = new net.minecraft.nbt.NbtList();
        for (float f : v) l.add(net.minecraft.nbt.NbtFloat.of(f));
        return l;
    }

    public void remove(ServerPlayerEntity p) {
        shown.remove(p.getUuid());
        DisplayEntity.TextDisplayEntity d = tags.remove(p.getUuid());
        if (d != null) d.discard();
    }

    public void clear() {
        for (DisplayEntity.TextDisplayEntity d : tags.values()) d.discard();
        tags.clear();
        shown.clear();
    }

    /** Leftover tags (e.g. after a crash) are removed as soon as they load. */
    public boolean isStray(Entity e) {
        return e.getCommandTags().contains(TAG) && !tags.containsValue(e);
    }
}
