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

    /** One party member as seen by another. */
    public record PartyMember(java.util.UUID id, String name, int level, int discipline, float health, float maxHealth,
                              float stamina, boolean online, boolean leader, double x, double y, double z, boolean sameWorld) {
        void write(PacketByteBuf b) {
            b.writeUuid(id);
            b.writeString(name);
            b.writeVarInt(level);
            b.writeVarInt(discipline);
            b.writeFloat(health);
            b.writeFloat(maxHealth);
            b.writeFloat(stamina);
            b.writeBoolean(online);
            b.writeBoolean(leader);
            b.writeDouble(x);
            b.writeDouble(y);
            b.writeDouble(z);
            b.writeBoolean(sameWorld);
        }

        static PartyMember read(PacketByteBuf b) {
            return new PartyMember(b.readUuid(), b.readString(), b.readVarInt(), b.readVarInt(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readBoolean(), b.readBoolean(), b.readDouble(), b.readDouble(), b.readDouble(), b.readBoolean());
        }
    }

    /** Server -> client: the other members of your party (empty = no party). */
    public record PartySync(java.util.List<PartyMember> members) implements CustomPayload {
        public static final Id<PartySync> ID = id("party");
        public static final PacketCodec<RegistryByteBuf, PartySync> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.members.size());
            for (PartyMember m : v.members) m.write(b);
        }, b -> {
            int n = Math.min(b.readVarInt(), 16);
            java.util.List<PartyMember> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(PartyMember.read(b));
            return new PartySync(l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record RosterEntry(java.util.UUID id, String name, int level, int discipline) { }

    /** Server -> client: character names of everyone online, for name plates. */
    public record Roster(java.util.List<RosterEntry> players) implements CustomPayload {
        public static final Id<Roster> ID = id("roster");
        public static final PacketCodec<RegistryByteBuf, Roster> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.players.size());
            for (RosterEntry e : v.players) {
                b.writeUuid(e.id());
                b.writeString(e.name());
                b.writeVarInt(e.level());
                b.writeVarInt(e.discipline());
            }
        }, b -> {
            int n = Math.min(b.readVarInt(), 1000);
            java.util.List<RosterEntry> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(new RosterEntry(b.readUuid(), b.readString(), b.readVarInt(), b.readVarInt()));
            return new Roster(l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: the current story objective (and its map marker). */
    public record Objective(String chapter, String text, String progress, boolean hasTarget, int x, int y, int z)
            implements CustomPayload {
        public static final Id<Objective> ID = id("objective");
        public static final PacketCodec<RegistryByteBuf, Objective> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.chapter);
            b.writeString(v.text);
            b.writeString(v.progress);
            b.writeBoolean(v.hasTarget);
            b.writeVarInt(v.x);
            b.writeVarInt(v.y);
            b.writeVarInt(v.z);
        }, b -> new Objective(b.readString(), b.readString(), b.readString(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open my satchel. */
    public record OpenSatchel() implements CustomPayload {
        public static final Id<OpenSatchel> ID = id("open_satchel");
        public static final PacketCodec<RegistryByteBuf, OpenSatchel> CODEC = PacketCodec.unit(new OpenSatchel());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record RecipeStatus(int recipe, int craftable, int[] have) { }

    /** Server -> client: cooking screen contents (open = open the screen, else refresh). */
    public record CookingState(boolean open, java.util.List<RecipeStatus> recipes) implements CustomPayload {
        public static final Id<CookingState> ID = id("cooking");
        public static final PacketCodec<RegistryByteBuf, CookingState> CODEC = PacketCodec.of((v, b) -> {
            b.writeBoolean(v.open);
            b.writeVarInt(v.recipes.size());
            for (RecipeStatus r : v.recipes) {
                b.writeVarInt(r.recipe());
                b.writeVarInt(r.craftable());
                b.writeIntArray(r.have());
            }
        }, b -> {
            boolean open = b.readBoolean();
            int n = Math.min(b.readVarInt(), 64);
            java.util.List<RecipeStatus> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(new RecipeStatus(b.readVarInt(), b.readVarInt(), b.readIntArray()));
            return new CookingState(open, l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: cook a recipe some number of times. */
    public record Cook(int recipe, int times) implements CustomPayload {
        public static final Id<Cook> ID = id("cook");
        public static final PacketCodec<RegistryByteBuf, Cook> CODEC =
            PacketCodec.of((v, b) -> { b.writeVarInt(v.recipe); b.writeVarInt(v.times); }, b -> new Cook(b.readVarInt(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: where the cooking fires are (for the minimap). */
    public record Campfires(int[] xyz) implements CustomPayload {
        public static final Id<Campfires> ID = id("campfires");
        public static final PacketCodec<RegistryByteBuf, Campfires> CODEC =
            PacketCodec.of((v, b) -> b.writeIntArray(v.xyz), b -> new Campfires(b.readIntArray()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: what happened when you died. */
    public record DeathInfo(String message, String killer, String mode, long xpLost, int gearWorn) implements CustomPayload {
        public static final Id<DeathInfo> ID = id("death");
        public static final PacketCodec<RegistryByteBuf, DeathInfo> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.message);
            b.writeString(v.killer);
            b.writeString(v.mode);
            b.writeVarLong(v.xpLost);
            b.writeVarInt(v.gearWorn);
        }, b -> new DeathInfo(b.readString(), b.readString(), b.readString(), b.readVarLong(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** A named area of the map: level range and title colour theme. */
    public record Area(String id, String name, String sub, int min, int max, int titans, String look, int prio, int x, int y, int z) { }

    /** Server -> client: all named areas (for the world map). */
    public record Areas(java.util.List<Area> areas) implements CustomPayload {
        public static final Id<Areas> ID = id("areas");
        public static final PacketCodec<RegistryByteBuf, Areas> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.areas.size());
            for (Area a : v.areas) {
                b.writeString(a.id()); b.writeString(a.name()); b.writeString(a.sub());
                b.writeVarInt(a.min()); b.writeVarInt(a.max()); b.writeVarInt(a.titans()); b.writeString(a.look());
                b.writeVarInt(a.prio()); b.writeVarInt(a.x()); b.writeVarInt(a.y()); b.writeVarInt(a.z());
            }
        }, b -> {
            int n = Math.min(b.readVarInt(), 2000);
            java.util.List<Area> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(new Area(b.readString(), b.readString(), b.readString(), b.readVarInt(), b.readVarInt(),
                b.readVarInt(), b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
            return new Areas(l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** One map image: the world map ("world") or a town plan tile. */
    public record MapFile(String name, String hash, int x0, int z0, int bpp, int size) { }

    /** Server -> client: the map images (hashes let the client use cached copies). */
    public record MapFiles(boolean outdated, java.util.List<MapFile> files) implements CustomPayload {
        public static final Id<MapFiles> ID = id("map_files");
        public static final PacketCodec<RegistryByteBuf, MapFiles> CODEC = PacketCodec.of((v, b) -> {
            b.writeBoolean(v.outdated);
            b.writeVarInt(v.files.size());
            for (MapFile f : v.files) {
                b.writeString(f.name()); b.writeString(f.hash()); b.writeVarInt(f.x0()); b.writeVarInt(f.z0());
                b.writeVarInt(f.bpp()); b.writeVarInt(f.size());
            }
        }, b -> {
            boolean outdated = b.readBoolean();
            int n = Math.min(b.readVarInt(), 4096);
            java.util.List<MapFile> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(new MapFile(b.readString(), b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
            return new MapFiles(outdated, l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: please send this map image. */
    public record MapRequest(String name) implements CustomPayload {
        public static final Id<MapRequest> ID = id("map_request");
        public static final PacketCodec<RegistryByteBuf, MapRequest> CODEC =
            PacketCodec.of((v, b) -> b.writeString(v.name), b -> new MapRequest(b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: one piece of a map image. */
    public record MapChunk(String name, int index, int total, byte[] data) implements CustomPayload {
        public static final Id<MapChunk> ID = id("map_chunk");
        public static final PacketCodec<RegistryByteBuf, MapChunk> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.name); b.writeVarInt(v.index); b.writeVarInt(v.total); b.writeByteArray(v.data);
        }, b -> new MapChunk(b.readString(), b.readVarInt(), b.readVarInt(), b.readByteArray(1 << 20)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** One quest as the journal shows it. state: 0 available, 1 active, 2 complete. */
    public record QuestView(String id, String title, String category, String text, int level, int state, String progress,
                            long xp, boolean tracked, String highlightedBy, boolean hasTarget, int x, int z) { }

    /** Server -> client: the quest journal. */
    public record Quests(java.util.List<QuestView> quests) implements CustomPayload {
        public static final Id<Quests> ID = id("quests");
        public static final PacketCodec<RegistryByteBuf, Quests> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.quests.size());
            for (QuestView q : v.quests) {
                b.writeString(q.id()); b.writeString(q.title()); b.writeString(q.category()); b.writeString(q.text());
                b.writeVarInt(q.level()); b.writeVarInt(q.state()); b.writeString(q.progress()); b.writeVarLong(q.xp());
                b.writeBoolean(q.tracked()); b.writeString(q.highlightedBy()); b.writeBoolean(q.hasTarget());
                b.writeVarInt(q.x()); b.writeVarInt(q.z());
            }
        }, b -> {
            int n = Math.min(b.readVarInt(), 2000);
            java.util.List<QuestView> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(new QuestView(b.readString(), b.readString(), b.readString(), b.readString(),
                b.readVarInt(), b.readVarInt(), b.readString(), b.readVarLong(), b.readBoolean(), b.readString(), b.readBoolean(),
                b.readVarInt(), b.readVarInt()));
            return new Quests(l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: accept, abandon, track or party-highlight a quest. */
    public record QuestAction(String quest, String action) implements CustomPayload {
        public static final Id<QuestAction> ID = id("quest_action");
        public static final PacketCodec<RegistryByteBuf, QuestAction> CODEC =
            PacketCodec.of((v, b) -> { b.writeString(v.quest); b.writeString(v.action); }, b -> new QuestAction(b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: set (or clear) my map waypoint. */
    public record SetWaypoint(boolean clear, int x, int z) implements CustomPayload {
        public static final Id<SetWaypoint> ID = id("waypoint");
        public static final PacketCodec<RegistryByteBuf, SetWaypoint> CODEC = PacketCodec.of((v, b) -> {
            b.writeBoolean(v.clear); b.writeVarInt(v.x); b.writeVarInt(v.z);
        }, b -> new SetWaypoint(b.readBoolean(), b.readVarInt(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** A marker for the map, minimap and world beams. kind: quest, mark, party_mark, party_quest. */
    public record Marker(String kind, String label, int x, int y, int z, int color) { }

    /** Server -> client: every marker this player should see. */
    public record Markers(java.util.List<Marker> markers) implements CustomPayload {
        public static final Id<Markers> ID = id("markers");
        public static final PacketCodec<RegistryByteBuf, Markers> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.markers.size());
            for (Marker m : v.markers) {
                b.writeString(m.kind()); b.writeString(m.label()); b.writeVarInt(m.x()); b.writeVarInt(m.y()); b.writeVarInt(m.z());
                b.writeInt(m.color());
            }
        }, b -> {
            int n = Math.min(b.readVarInt(), 256);
            java.util.List<Marker> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(new Marker(b.readString(), b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readInt()));
            return new Markers(l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: send me the world data again (map, areas, campfires, quests). */
    public record WorldDataRequest() implements CustomPayload {
        public static final Id<WorldDataRequest> ID = id("world_data");
        public static final PacketCodec<RegistryByteBuf, WorldDataRequest> CODEC = PacketCodec.unit(new WorldDataRequest());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: use the quick-heal. */
    public record QuickHealUse() implements CustomPayload {
        public static final Id<QuickHealUse> ID = id("quick_heal");
        public static final PacketCodec<RegistryByteBuf, QuickHealUse> CODEC = PacketCodec.unit(new QuickHealUse());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: the next heal item (registry id), how many, and the cooldown. */
    public record HealInfo(String item, int count, int cooldown, int cooldownMax) implements CustomPayload {
        public static final Id<HealInfo> ID = id("heal_info");
        public static final PacketCodec<RegistryByteBuf, HealInfo> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.item); b.writeVarInt(v.count); b.writeVarInt(v.cooldown); b.writeVarInt(v.cooldownMax);
        }, b -> new HealInfo(b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: unlocked cosmetics and what is selected (trail first). */
    public record CosmeticsSync(java.util.List<String> unlocked, java.util.List<String> selected, boolean allowlisted) implements CustomPayload {
        public static final Id<CosmeticsSync> ID = id("cosmetics");
        public static final PacketCodec<RegistryByteBuf, CosmeticsSync> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.unlocked.size());
            for (String s : v.unlocked) b.writeString(s);
            b.writeVarInt(v.selected.size());
            for (String s : v.selected) b.writeString(s);
            b.writeBoolean(v.allowlisted);
        }, b -> {
            int n = Math.min(b.readVarInt(), 256);
            java.util.List<String> u = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) u.add(b.readString());
            int m = Math.min(b.readVarInt(), 32);
            java.util.List<String> s = new java.util.ArrayList<>();
            for (int i = 0; i < m; i++) s.add(b.readString());
            return new CosmeticsSync(u, s, b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: select a cosmetic. */
    public record SelectCosmetic(String cosmetic) implements CustomPayload {
        public static final Id<SelectCosmetic> ID = id("select_cosmetic");
        public static final PacketCodec<RegistryByteBuf, SelectCosmetic> CODEC =
            PacketCodec.of((v, b) -> b.writeString(v.cosmetic), b -> new SelectCosmetic(b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: a shot's trail from start to end in the shooter's chosen style. */
    public record Trail(String style, double x0, double y0, double z0, double x1, double y1, double z1) implements CustomPayload {
        public static final Id<Trail> ID = id("trail");
        public static final PacketCodec<RegistryByteBuf, Trail> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.style);
            b.writeDouble(v.x0); b.writeDouble(v.y0); b.writeDouble(v.z0);
            b.writeDouble(v.x1); b.writeDouble(v.y1); b.writeDouble(v.z1);
        }, b -> new Trail(b.readString(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: the player fired the APG gun (left click). */
    public record ShotFired() implements CustomPayload {
        public static final Id<ShotFired> ID = id("shot");
        public static final PacketCodec<RegistryByteBuf, ShotFired> CODEC = PacketCodec.unit(new ShotFired());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: how many ODM grips a player has sheathed on their back (0-2), and which item. */
    public record SheathState(java.util.UUID player, String item, int count) implements CustomPayload {
        public static final Id<SheathState> ID = id("sheath");
        public static final PacketCodec<RegistryByteBuf, SheathState> CODEC = PacketCodec.of((v, b) -> {
            b.writeUuid(v.player); b.writeString(v.item); b.writeVarInt(v.count);
        }, b -> new SheathState(b.readUuid(), b.readString(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: the sheath key (draw or sheathe the ODM grips). */
    public record ToggleSheath() implements CustomPayload {
        public static final Id<ToggleSheath> ID = id("toggle_sheath");
        public static final PacketCodec<RegistryByteBuf, ToggleSheath> CODEC = PacketCodec.unit(new ToggleSheath());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: a strike at the eye of the titan holding you. */
    public record Struggle() implements CustomPayload {
        public static final Id<Struggle> ID = id("struggle");
        public static final PacketCodec<RegistryByteBuf, Struggle> CODEC = PacketCodec.unit(new Struggle());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: Marks (this character) and Gold (account). */
    public record WalletSync(long marks, long gold) implements CustomPayload {
        public static final Id<WalletSync> ID = id("wallet");
        public static final PacketCodec<RegistryByteBuf, WalletSync> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarLong(v.marks); b.writeVarLong(v.gold);
        }, b -> new WalletSync(b.readVarLong(), b.readVarLong()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record CharacterEntry(int slot, boolean created, String name, int level, int discipline, int origin,
                                 long marks, int rerolls, long lastPlayed) {
        void write(PacketByteBuf b) {
            b.writeVarInt(slot); b.writeBoolean(created); b.writeString(name); b.writeVarInt(level);
            b.writeVarInt(discipline); b.writeVarInt(origin); b.writeVarLong(marks); b.writeVarInt(rerolls); b.writeLong(lastPlayed);
        }

        static CharacterEntry read(PacketByteBuf b) {
            return new CharacterEntry(b.readVarInt(), b.readBoolean(), b.readString(), b.readVarInt(), b.readVarInt(),
                b.readVarInt(), b.readVarLong(), b.readVarInt(), b.readLong());
        }
    }

    /** Server -> client: the player's characters; open = show the select screen. */
    public record CharacterList(java.util.List<CharacterEntry> list, int active, int max, int maxRerolls, boolean open) implements CustomPayload {
        public static final Id<CharacterList> ID = id("characters");
        public static final PacketCodec<RegistryByteBuf, CharacterList> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.list.size());
            for (CharacterEntry e : v.list) e.write(b);
            b.writeVarInt(v.active); b.writeVarInt(v.max); b.writeVarInt(v.maxRerolls); b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 16);
            java.util.List<CharacterEntry> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(CharacterEntry.read(b));
            return new CharacterList(l, b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: play / new / delete / list. */
    public record CharacterAction(String action, int slot) implements CustomPayload {
        public static final Id<CharacterAction> ID = id("character_action");
        public static final PacketCodec<RegistryByteBuf, CharacterAction> CODEC =
            PacketCodec.of((v, b) -> { b.writeString(v.action); b.writeVarInt(v.slot); }, b -> new CharacterAction(b.readString(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: you hit something. kind bits: 1 ranged, 2 kill, 4 player, 8 titan. */
    public record HitMarker(int entity, float damage, int kind) implements CustomPayload {
        public static final Id<HitMarker> ID = id("hit");
        public static final PacketCodec<RegistryByteBuf, HitMarker> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.entity); b.writeFloat(v.damage); b.writeVarInt(v.kind);
        }, b -> new HitMarker(b.readVarInt(), b.readFloat(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    static void register() {
        PayloadTypeRegistry.playS2C().register(HitMarker.ID, HitMarker.CODEC);
        PayloadTypeRegistry.playS2C().register(WalletSync.ID, WalletSync.CODEC);
        PayloadTypeRegistry.playS2C().register(CharacterList.ID, CharacterList.CODEC);
        PayloadTypeRegistry.playC2S().register(CharacterAction.ID, CharacterAction.CODEC);
        PayloadTypeRegistry.playC2S().register(Struggle.ID, Struggle.CODEC);
        PayloadTypeRegistry.playC2S().register(ToggleSheath.ID, ToggleSheath.CODEC);
        PayloadTypeRegistry.playS2C().register(SheathState.ID, SheathState.CODEC);
        PayloadTypeRegistry.playC2S().register(ShotFired.ID, ShotFired.CODEC);
        PayloadTypeRegistry.playC2S().register(QuickHealUse.ID, QuickHealUse.CODEC);
        PayloadTypeRegistry.playS2C().register(HealInfo.ID, HealInfo.CODEC);
        PayloadTypeRegistry.playS2C().register(CosmeticsSync.ID, CosmeticsSync.CODEC);
        PayloadTypeRegistry.playC2S().register(SelectCosmetic.ID, SelectCosmetic.CODEC);
        PayloadTypeRegistry.playS2C().register(Trail.ID, Trail.CODEC);
        PayloadTypeRegistry.playC2S().register(WorldDataRequest.ID, WorldDataRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(Areas.ID, Areas.CODEC);
        PayloadTypeRegistry.playS2C().register(MapFiles.ID, MapFiles.CODEC);
        PayloadTypeRegistry.playC2S().register(MapRequest.ID, MapRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(MapChunk.ID, MapChunk.CODEC);
        PayloadTypeRegistry.playS2C().register(Quests.ID, Quests.CODEC);
        PayloadTypeRegistry.playC2S().register(QuestAction.ID, QuestAction.CODEC);
        PayloadTypeRegistry.playC2S().register(SetWaypoint.ID, SetWaypoint.CODEC);
        PayloadTypeRegistry.playS2C().register(Markers.ID, Markers.CODEC);
        PayloadTypeRegistry.playC2S().register(OpenSatchel.ID, OpenSatchel.CODEC);
        PayloadTypeRegistry.playS2C().register(CookingState.ID, CookingState.CODEC);
        PayloadTypeRegistry.playC2S().register(Cook.ID, Cook.CODEC);
        PayloadTypeRegistry.playS2C().register(Campfires.ID, Campfires.CODEC);
        PayloadTypeRegistry.playS2C().register(DeathInfo.ID, DeathInfo.CODEC);
        PayloadTypeRegistry.playS2C().register(Roster.ID, Roster.CODEC);
        PayloadTypeRegistry.playS2C().register(Objective.ID, Objective.CODEC);
        PayloadTypeRegistry.playS2C().register(PartySync.ID, PartySync.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenCreator.ID, OpenCreator.CODEC);
        PayloadTypeRegistry.playS2C().register(Sync.ID, Sync.CODEC);
        PayloadTypeRegistry.playS2C().register(StaminaSync.ID, StaminaSync.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenCharacter.ID, OpenCharacter.CODEC);
        PayloadTypeRegistry.playC2S().register(Create.ID, Create.CODEC);
        PayloadTypeRegistry.playC2S().register(SpendPoint.ID, SpendPoint.CODEC);
        PayloadTypeRegistry.playC2S().register(Learn.ID, Learn.CODEC);
    }
}
