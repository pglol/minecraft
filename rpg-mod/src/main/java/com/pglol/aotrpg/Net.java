package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Packets between the server and players who have the mod installed. */
public final class Net {
    private Net() {}

    private static <T extends CustomPayload> CustomPayload.Id<T> id(String path) {
        return new CustomPayload.Id<>(Identifier.of(AotRpg.MOD_ID, path));
    }

    /** Server -> client: open the character creator. error is shown if the last attempt was rejected. */
    public record OpenCreator(String error) implements CustomPayload {
        public static final Id<OpenCreator> ID = id("open_creator");
        public static final PacketCodec<RegistryByteBuf, OpenCreator> CODEC =
            PacketCodec.of((v, b) -> b.writeString(v.error), b -> new OpenCreator(b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: the finished character. */
    public record Create(int origin, int discipline, int[] stats, String first, String family) implements CustomPayload {
        public static final Id<Create> ID = id("create");
        public static final PacketCodec<RegistryByteBuf, Create> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.origin);
            b.writeVarInt(v.discipline);
            b.writeIntArray(v.stats);
            b.writeString(v.first);
            b.writeString(v.family);
        }, b -> new Create(b.readVarInt(), b.readVarInt(), b.readIntArray(), b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: everything the HUD and character screen show. */
    public record Sync(String name, int origin, int discipline, int level, long xp, long need, int points,
                       int skillPoints, int[] base, int[] total, int titanKills, int chapter, long skills)
            implements CustomPayload {
        public static final Id<Sync> ID = id("sync");
        public static final PacketCodec<RegistryByteBuf, Sync> CODEC = PacketCodec.of(Sync::write, Sync::read);
        @Override public Id<? extends CustomPayload> getId() { return ID; }

        private void write(PacketByteBuf b) {
            b.writeString(name);
            b.writeVarInt(origin);
            b.writeVarInt(discipline);
            b.writeVarInt(level);
            b.writeVarLong(xp);
            b.writeVarLong(need);
            b.writeVarInt(points);
            b.writeVarInt(skillPoints);
            b.writeIntArray(base);
            b.writeIntArray(total);
            b.writeVarInt(titanKills);
            b.writeVarInt(chapter);
            b.writeLong(skills);
        }

        private static Sync read(PacketByteBuf b) {
            return new Sync(b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarLong(),
                b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readIntArray(), b.readIntArray(),
                b.readVarInt(), b.readVarInt(), b.readLong());
        }

        public Origin originEnum() { return Origin.values()[origin]; }
        public Discipline disciplineEnum() { return Discipline.values()[discipline]; }
        public boolean has(Skill s) { return (skills & (1L << s.ordinal())) != 0; }

        public static Sync of(Profile p) {
            int[] base = new int[Stat.values().length], total = new int[base.length];
            for (Stat s : Stat.values()) {
                base[s.ordinal()] = p.stat(s);
                total[s.ordinal()] = p.total(s);
            }
            long mask = 0;
            for (Skill s : p.skills) mask |= 1L << s.ordinal();
            return new Sync(p.name, p.origin.ordinal(), p.discipline.ordinal(), p.level, p.xp, Profile.xpForNext(p.level),
                p.points, p.skillPoints, base, total, p.titanKills, p.chapter, mask);
        }
    }

    /** Server -> client: stamina, several times a second. */
    public record StaminaSync(float stamina, float max, boolean exhausted) implements CustomPayload {
        public static final Id<StaminaSync> ID = id("stamina");
        public static final PacketCodec<RegistryByteBuf, StaminaSync> CODEC = PacketCodec.of((v, b) -> {
            b.writeFloat(v.stamina);
            b.writeFloat(v.max);
            b.writeBoolean(v.exhausted);
        }, b -> new StaminaSync(b.readFloat(), b.readFloat(), b.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: open the character screen (from /character). */
    public record OpenCharacter(int tab) implements CustomPayload {
        public static final Id<OpenCharacter> ID = id("open_character");
        public static final PacketCodec<RegistryByteBuf, OpenCharacter> CODEC =
            PacketCodec.of((v, b) -> b.writeVarInt(v.tab), b -> new OpenCharacter(b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: spend a stat point. */
    public record SpendPoint(int stat) implements CustomPayload {
        public static final Id<SpendPoint> ID = id("spend_point");
        public static final PacketCodec<RegistryByteBuf, SpendPoint> CODEC =
            PacketCodec.of((v, b) -> b.writeVarInt(v.stat), b -> new SpendPoint(b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: learn a skill. */
    public record Learn(int skill) implements CustomPayload {
        public static final Id<Learn> ID = id("learn");
        public static final PacketCodec<RegistryByteBuf, Learn> CODEC =
            PacketCodec.of((v, b) -> b.writeVarInt(v.skill), b -> new Learn(b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    static void register() {
        PayloadTypeRegistry.playS2C().register(OpenCreator.ID, OpenCreator.CODEC);
        PayloadTypeRegistry.playS2C().register(Sync.ID, Sync.CODEC);
        PayloadTypeRegistry.playS2C().register(StaminaSync.ID, StaminaSync.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenCharacter.ID, OpenCharacter.CODEC);
        PayloadTypeRegistry.playC2S().register(Create.ID, Create.CODEC);
        PayloadTypeRegistry.playC2S().register(SpendPoint.ID, SpendPoint.CODEC);
        PayloadTypeRegistry.playC2S().register(Learn.ID, Learn.CODEC);
    }
}
