package com.pglol.aotrpg;

import net.minecraft.item.ItemStack;
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
                       int skillPoints, int[] base, int[] total, int titanKills, int chapter, long[] skills,
                       int[] crafts, int skillResets, int cls)
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
            b.writeLongArray(skills);
            b.writeIntArray(crafts);
            b.writeVarInt(skillResets);
            b.writeVarInt(cls);
        }

        private static Sync read(PacketByteBuf b) {
            return new Sync(b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarLong(),
                b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readIntArray(), b.readIntArray(),
                b.readVarInt(), b.readVarInt(), b.readLongArray(), b.readIntArray(), b.readVarInt(), b.readVarInt());
        }

        public Origin originEnum() { return Origin.values()[origin]; }
        public Discipline disciplineEnum() { return Discipline.values()[discipline]; }
        public boolean has(Skill s) {
            int i = s.ordinal() >> 6;
            return i < skills.length && (skills[i] & (1L << (s.ordinal() & 63))) != 0;
        }
        public boolean anySkill() {
            for (long l : skills) if (l != 0) return true;
            return false;
        }
        public PlayerClass role() { return PlayerClass.values()[Math.max(0, Math.min(PlayerClass.values().length - 1, cls))]; }

        public static Sync of(Profile p) {
            int[] base = new int[Stat.values().length], total = new int[base.length];
            for (Stat s : Stat.values()) {
                base[s.ordinal()] = p.stat(s);
                total[s.ordinal()] = p.total(s);
            }
            long[] mask = new long[(Skill.values().length + 63) / 64];
            for (Skill s : p.skills) mask[s.ordinal() >> 6] |= 1L << (s.ordinal() & 63);
            return new Sync(p.name, p.origin.ordinal(), p.discipline.ordinal(), p.level, p.xp, Profile.xpForNext(p.level),
                p.points, p.skillPoints, base, total, p.titanKills, p.chapter, mask, crafts(p), p.skillResets, p.cls().ordinal());
        }

        /** Smithing, fishing, cooking: level * 1000 + progress to the next level in thousandths. */
        private static int[] crafts(Profile p) {
            String[] skills = {Lifestyle.SMITHING, Lifestyle.FISHING, Lifestyle.COOKING};
            int[] out = new int[skills.length];
            for (int i = 0; i < skills.length; i++) {
                int lv = Lifestyle.level(p, skills[i]);
                long xp = p.lifestyle.getOrDefault(skills[i], 0L);
                double lo = 40.0 * lv * lv, hi = 40.0 * (lv + 1) * (lv + 1);
                int frac = lv >= Lifestyle.MAX ? 999 : (int) Math.max(0, Math.min(999, (xp - lo) / (hi - lo) * 1000));
                out[i] = lv * 1000 + frac;
            }
            return out;
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
    /** Client -> server: use ability slot 0 (Z), 1 (X) or 2 (the ultimate, V). */
    public record UseAbility(int slot) implements CustomPayload {
        public static final Id<UseAbility> ID = id("use_ability");
        public static final PacketCodec<RegistryByteBuf, UseAbility> CODEC =
            PacketCodec.of((v, b) -> b.writeVarInt(v.slot), b -> new UseAbility(b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: choose the role you play and show. */
    public record ChooseRole(int cls) implements CustomPayload {
        public static final Id<ChooseRole> ID = id("choose_role");
        public static final PacketCodec<RegistryByteBuf, ChooseRole> CODEC =
            PacketCodec.of((v, b) -> b.writeVarInt(v.cls), b -> new ChooseRole(b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: your ability bar: role, learned slots, cooldowns (ms), ultimate charge and time left, alone, in an event. */
    public record ClassHud(int cls, int mask, int[] cdLeft, int[] cdMax, float charge, int ultLeft, boolean alone, boolean event) implements CustomPayload {
        public static final Id<ClassHud> ID = id("class_hud");
        public static final PacketCodec<RegistryByteBuf, ClassHud> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.cls); b.writeVarInt(v.mask); b.writeIntArray(v.cdLeft); b.writeIntArray(v.cdMax);
            b.writeFloat(v.charge); b.writeVarInt(v.ultLeft); b.writeBoolean(v.alone); b.writeBoolean(v.event);
        }, b -> new ClassHud(b.readVarInt(), b.readVarInt(), b.readIntArray(), b.readIntArray(), b.readFloat(), b.readVarInt(), b.readBoolean(), b.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

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

    public record RosterEntry(java.util.UUID id, String name, int level, int discipline, String tag, int tagColor, boolean rp, int faction, String regiment, int regimentColor, int cls) { }

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
                b.writeString(e.tag());
                b.writeInt(e.tagColor());
                b.writeBoolean(e.rp());
                b.writeVarInt(e.faction() + 1);
                b.writeString(e.regiment());
                b.writeInt(e.regimentColor());
                b.writeVarInt(e.cls());
            }
        }, b -> {
            int n = Math.min(b.readVarInt(), 1000);
            java.util.List<RosterEntry> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(new RosterEntry(b.readUuid(), b.readString(), b.readVarInt(), b.readVarInt(), b.readString(), b.readInt(), b.readBoolean(), b.readVarInt() - 1, b.readString(), b.readInt(), b.readVarInt()));
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
    /** A titan's level and nape progress, for its name plate. */
    public record TitanTag(int entity, int level, int strikes, int needed) { }

    /** Server -> client: the titans around you. */
    public record TitanTags(java.util.List<TitanTag> tags) implements CustomPayload {
        public static final Id<TitanTags> ID = id("titan_tags");
        public static final PacketCodec<RegistryByteBuf, TitanTags> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.tags.size());
            for (TitanTag t : v.tags) { b.writeVarInt(t.entity()); b.writeVarInt(t.level()); b.writeVarInt(t.strikes()); b.writeVarInt(t.needed()); }
        }, b -> {
            int n = Math.min(b.readVarInt(), 128);
            java.util.List<TitanTag> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new TitanTag(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
            return new TitanTags(l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: a nape strike landed (and whether it felled the titan). */
    public record NapeHit(int entity, int strikes, int needed, boolean kill) implements CustomPayload {
        public static final Id<NapeHit> ID = id("nape_hit");
        public static final PacketCodec<RegistryByteBuf, NapeHit> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.entity); b.writeVarInt(v.strikes); b.writeVarInt(v.needed); b.writeBoolean(v.kill);
        }, b -> new NapeHit(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record BagEntry(int slot, net.minecraft.item.ItemStack stack) { }

    /** Server -> client: the satchel's contents. */
    public record BagView(java.util.List<BagEntry> items, int size, boolean open) implements CustomPayload {
        public static final Id<BagView> ID = id("bag_view");
        public static final PacketCodec<RegistryByteBuf, BagView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.items.size());
            for (BagEntry e : v.items) { b.writeVarInt(e.slot()); net.minecraft.item.ItemStack.OPTIONAL_PACKET_CODEC.encode(b, e.stack()); }
            b.writeVarInt(v.size); b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 1024);
            java.util.List<BagEntry> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new BagEntry(b.readVarInt(), net.minecraft.item.ItemStack.OPTIONAL_PACKET_CODEC.decode(b)));
            return new BagView(l, b.readVarInt(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: a satchel button (equip, use, drop, list with a price). */
    public record BagAction(String action, int slot, long arg) implements CustomPayload {
        public static final Id<BagAction> ID = id("bag_action");
        public static final PacketCodec<RegistryByteBuf, BagAction> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.action); b.writeVarInt(v.slot); b.writeVarLong(v.arg);
        }, b -> new BagAction(b.readString(), b.readVarInt(), b.readVarLong()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record RegimentInfo(String id, String name, String tag, int color, int level, long xp, long xpLevel, long xpNext,
                               int members, int cap, boolean open, String captain, long treasury) { }
    public record RegimentMember(String uuid, String name, String role, boolean online, long contrib, int level) { }

    private static void writeReg(RegistryByteBuf b, RegimentInfo r) {
        b.writeString(r.id()); b.writeString(r.name()); b.writeString(r.tag()); b.writeInt(r.color()); b.writeVarInt(r.level());
        b.writeVarLong(r.xp()); b.writeVarLong(r.xpLevel()); b.writeVarLong(r.xpNext()); b.writeVarInt(r.members()); b.writeVarInt(r.cap());
        b.writeBoolean(r.open()); b.writeString(r.captain()); b.writeVarLong(r.treasury());
    }

    private static RegimentInfo readReg(RegistryByteBuf b) {
        return new RegimentInfo(b.readString(), b.readString(), b.readString(), b.readInt(), b.readVarInt(), b.readVarLong(), b.readVarLong(),
            b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readString(), b.readVarLong());
    }

    /** Server -> client: your regiment (or null), its members, every regiment, and your invites. */
    public record RegimentView(RegimentInfo mine, String role, java.util.List<RegimentMember> members, java.util.List<RegimentInfo> all,
                               java.util.List<RegimentInfo> invites, String motto, long cost, int minLevel, boolean open) implements CustomPayload {
        public static final Id<RegimentView> ID = id("regiments");
        public static final PacketCodec<RegistryByteBuf, RegimentView> CODEC = PacketCodec.of((v, b) -> {
            b.writeBoolean(v.mine != null);
            if (v.mine != null) writeReg(b, v.mine);
            b.writeString(v.role);
            b.writeVarInt(v.members.size());
            for (RegimentMember m : v.members) {
                b.writeString(m.uuid()); b.writeString(m.name()); b.writeString(m.role()); b.writeBoolean(m.online());
                b.writeVarLong(m.contrib()); b.writeVarInt(m.level());
            }
            b.writeVarInt(v.all.size());
            for (RegimentInfo r : v.all) writeReg(b, r);
            b.writeVarInt(v.invites.size());
            for (RegimentInfo r : v.invites) writeReg(b, r);
            b.writeString(v.motto); b.writeVarLong(v.cost); b.writeVarInt(v.minLevel); b.writeBoolean(v.open);
        }, b -> {
            RegimentInfo mine = b.readBoolean() ? readReg(b) : null;
            String role = b.readString();
            int n = Math.min(b.readVarInt(), 64);
            java.util.List<RegimentMember> ms = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) ms.add(new RegimentMember(b.readString(), b.readString(), b.readString(), b.readBoolean(), b.readVarLong(), b.readVarInt()));
            int k = Math.min(b.readVarInt(), 500);
            java.util.List<RegimentInfo> all = new java.util.ArrayList<>();
            for (int i = 0; i < k; i++) all.add(readReg(b));
            int q = Math.min(b.readVarInt(), 64);
            java.util.List<RegimentInfo> inv = new java.util.ArrayList<>();
            for (int i = 0; i < q; i++) inv.add(readReg(b));
            return new RegimentView(mine, role, ms, all, inv, b.readString(), b.readVarLong(), b.readVarInt(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open/create/join/decline/invite/leave/kick/promote/demote/captain/disband/deposit/toggle_open/motto. */
    public record RegimentAction(String action, String arg) implements CustomPayload {
        public static final Id<RegimentAction> ID = id("regiment_action");
        public static final PacketCodec<RegistryByteBuf, RegimentAction> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.action); b.writeString(v.arg);
        }, b -> new RegimentAction(b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record RaidBoss(String id, String name, int level, boolean available) { }

    /** Server -> client: the Raid Commander's board. */
    public record RaidView(java.util.List<RaidBoss> bosses, java.util.List<String> party, String active, boolean open) implements CustomPayload {
        public static final Id<RaidView> ID = id("raid_view");
        public static final PacketCodec<RegistryByteBuf, RaidView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.bosses.size());
            for (RaidBoss r : v.bosses) { b.writeString(r.id()); b.writeString(r.name()); b.writeVarInt(r.level()); b.writeBoolean(r.available()); }
            b.writeVarInt(v.party.size());
            for (String s : v.party) b.writeString(s);
            b.writeString(v.active); b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 32);
            java.util.List<RaidBoss> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new RaidBoss(b.readString(), b.readString(), b.readVarInt(), b.readBoolean()));
            int k = Math.min(b.readVarInt(), 8);
            java.util.List<String> p = new java.util.ArrayList<>();
            for (int i = 0; i < k; i++) p.add(b.readString());
            return new RaidView(l, p, b.readString(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: start a raid (boss, difficulty) or refresh. */
    public record RaidAction(String action, String boss, int difficulty) implements CustomPayload {
        public static final Id<RaidAction> ID = id("raid_action");
        public static final PacketCodec<RegistryByteBuf, RaidAction> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.action); b.writeString(v.boss); b.writeVarInt(v.difficulty);
        }, b -> new RaidAction(b.readString(), b.readString(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record EstateProject(String id, String title, String desc, long price, int state, float progress) { }
    public record EstatePet(String id, String name, String desc, long price, boolean owned, boolean companion) { }

    /** Server -> client: your estate (projects, produce) and pets. */
    public record EstateView(boolean hasPlot, String plot, int fort, java.util.List<EstateProject> projects, int harvest, long nextHarvest,
                             java.util.List<EstatePet> pets, boolean atHome, boolean respawnHome, boolean open) implements CustomPayload {
        public static final Id<EstateView> ID = id("estate_view");
        public static final PacketCodec<RegistryByteBuf, EstateView> CODEC = PacketCodec.of((v, b) -> {
            b.writeBoolean(v.hasPlot); b.writeString(v.plot); b.writeVarInt(v.fort);
            b.writeVarInt(v.projects.size());
            for (EstateProject p : v.projects) {
                b.writeString(p.id()); b.writeString(p.title()); b.writeString(p.desc()); b.writeVarLong(p.price()); b.writeVarInt(p.state()); b.writeFloat(p.progress());
            }
            b.writeVarInt(v.harvest); b.writeLong(v.nextHarvest);
            b.writeVarInt(v.pets.size());
            for (EstatePet p : v.pets) {
                b.writeString(p.id()); b.writeString(p.name()); b.writeString(p.desc()); b.writeVarLong(p.price()); b.writeBoolean(p.owned()); b.writeBoolean(p.companion());
            }
            b.writeBoolean(v.atHome); b.writeBoolean(v.respawnHome); b.writeBoolean(v.open);
        }, b -> {
            boolean has = b.readBoolean();
            String plot = b.readString();
            int fort = b.readVarInt();
            int n = Math.min(b.readVarInt(), 32);
            java.util.List<EstateProject> ps = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) ps.add(new EstateProject(b.readString(), b.readString(), b.readString(), b.readVarLong(), b.readVarInt(), b.readFloat()));
            int harvest = b.readVarInt();
            long next = b.readLong();
            int k = Math.min(b.readVarInt(), 64);
            java.util.List<EstatePet> pets = new java.util.ArrayList<>();
            for (int i = 0; i < k; i++) pets.add(new EstatePet(b.readString(), b.readString(), b.readString(), b.readVarLong(), b.readBoolean(), b.readBoolean()));
            return new EstateView(has, plot, fort, ps, harvest, next, pets, b.readBoolean(), b.readBoolean(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open/view, build (project), harvest, buypet (id), companion (id). */
    public record EstateAction(String action, String arg) implements CustomPayload {
        public static final Id<EstateAction> ID = id("estate_action");
        public static final PacketCodec<RegistryByteBuf, EstateAction> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.action); b.writeString(v.arg);
        }, b -> new EstateAction(b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record StatLine(String label, String value) { }
    public record StatBoard(String title, java.util.List<StatLine> rows) { }

    private static void writeLines(RegistryByteBuf b, java.util.List<StatLine> l) {
        b.writeVarInt(l.size());
        for (StatLine s : l) { b.writeString(s.label()); b.writeString(s.value()); }
    }

    private static java.util.List<StatLine> readLines(RegistryByteBuf b) {
        int n = Math.min(b.readVarInt(), 64);
        java.util.List<StatLine> l = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new StatLine(b.readString(), b.readString()));
        return l;
    }

    /** Server -> client: personal stats, server stats and leaderboards. */
    public record StatsView(java.util.List<StatLine> mine, java.util.List<StatLine> server, java.util.List<StatBoard> boards) implements CustomPayload {
        public static final Id<StatsView> ID = id("stats");
        public static final PacketCodec<RegistryByteBuf, StatsView> CODEC = PacketCodec.of((v, b) -> {
            writeLines(b, v.mine); writeLines(b, v.server);
            b.writeVarInt(v.boards.size());
            for (StatBoard s : v.boards) { b.writeString(s.title()); writeLines(b, s.rows()); }
        }, b -> {
            java.util.List<StatLine> mine = readLines(b), server = readLines(b);
            int n = Math.min(b.readVarInt(), 16);
            java.util.List<StatBoard> boards = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) boards.add(new StatBoard(b.readString(), readLines(b)));
            return new StatsView(mine, server, boards);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: send me the stats. */
    public record StatsRequest() implements CustomPayload {
        public static final Id<StatsRequest> ID = id("stats_request");
        public static final PacketCodec<RegistryByteBuf, StatsRequest> CODEC = PacketCodec.unit(new StatsRequest());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

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
    public record Cook(int recipe, int times, float quality) implements CustomPayload {
        public static final Id<Cook> ID = id("cook");
        public static final PacketCodec<RegistryByteBuf, Cook> CODEC =
            PacketCodec.of((v, b) -> { b.writeVarInt(v.recipe); b.writeVarInt(v.times); b.writeFloat(v.quality); },
                b -> new Cook(b.readVarInt(), b.readVarInt(), b.readFloat()));
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

    /** Server -> client: what one player wears ("slot=id" each), for drawing their effects. */
    public record CosmeticsOf(java.util.UUID player, java.util.List<String> worn) implements CustomPayload {
        public static final Id<CosmeticsOf> ID = id("cosmetics_of");
        public static final PacketCodec<RegistryByteBuf, CosmeticsOf> CODEC = PacketCodec.of((v, b) -> {
            b.writeUuid(v.player);
            b.writeVarInt(v.worn.size());
            for (String s : v.worn) b.writeString(s);
        }, b -> {
            java.util.UUID id = b.readUuid();
            int n = Math.min(b.readVarInt(), 32);
            java.util.List<String> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(b.readString());
            return new CosmeticsOf(id, l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> nearby clients: a blade slash landed, drawn in the attacker's slash style. */
    public record SlashFx(String style, double x, double y, double z, float yaw) implements CustomPayload {
        public static final Id<SlashFx> ID = id("slash_fx");
        public static final PacketCodec<RegistryByteBuf, SlashFx> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.style); b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z); b.writeFloat(v.yaw);
        }, b -> new SlashFx(b.readString(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: my blade swing struck this entity (for the slash effect). */
    public record SlashHit(int entity) implements CustomPayload {
        public static final Id<SlashHit> ID = id("slash_hit");
        public static final PacketCodec<RegistryByteBuf, SlashHit> CODEC =
            PacketCodec.of((v, b) -> b.writeVarInt(v.entity), b -> new SlashHit(b.readVarInt()));
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
    /** Server -> client: the grips sheathed on a player's back (empty stacks for none), exactly as they are. */
    public record SheathState(java.util.UUID player, ItemStack a, ItemStack b) implements CustomPayload {
        public static final Id<SheathState> ID = id("sheath");
        public static final PacketCodec<RegistryByteBuf, SheathState> CODEC = PacketCodec.of((v, b) -> {
            b.writeUuid(v.player);
            ItemStack.OPTIONAL_PACKET_CODEC.encode(b, v.a);
            ItemStack.OPTIONAL_PACKET_CODEC.encode(b, v.b);
        }, b -> new SheathState(b.readUuid(), ItemStack.OPTIONAL_PACKET_CODEC.decode(b), ItemStack.OPTIONAL_PACKET_CODEC.decode(b)));

        public int count() {
            return (a.isEmpty() ? 0 : 1) + (b.isEmpty() ? 0 : 1);
        }
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: the sheath key (draw or sheathe the ODM grips). */
    public record ToggleSheath() implements CustomPayload {
        public static final Id<ToggleSheath> ID = id("toggle_sheath");
        public static final PacketCodec<RegistryByteBuf, ToggleSheath> CODEC = PacketCodec.unit(new ToggleSheath());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: a strike at the eye of the titan holding you. */
    /** Server -> client: a fish bit, play the reel minigame. */
    public record FishBite(float difficulty) implements CustomPayload {
        public static final Id<FishBite> ID = id("fish_bite");
        public static final PacketCodec<RegistryByteBuf, FishBite> CODEC =
            PacketCodec.of((v, b) -> b.writeFloat(v.difficulty), b -> new FishBite(b.readFloat()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: how well the fish was reeled (0 = it got away). */
    public record FishResult(float quality) implements CustomPayload {
        public static final Id<FishResult> ID = id("fish_result");
        public static final PacketCodec<RegistryByteBuf, FishResult> CODEC =
            PacketCodec.of((v, b) -> b.writeFloat(v.quality), b -> new FishResult(b.readFloat()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

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

    public record MarketGood(String item, String category, long buy, long sell, int stock, int trend, int have) {
        void write(RegistryByteBuf b) {
            b.writeString(item); b.writeString(category); b.writeVarLong(buy); b.writeVarLong(sell);
            b.writeVarInt(stock); b.writeVarInt(trend); b.writeVarInt(have);
        }

        static MarketGood read(RegistryByteBuf b) {
            return new MarketGood(b.readString(), b.readString(), b.readVarLong(), b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readVarInt());
        }
    }

    public record GearOffer(int slot, long value) { }

    /** Server -> client: a town market (goods with local prices, your sellable gear). */
    public record MarketView(String town, String sector, String controller, int discount, java.util.List<MarketGood> goods,
                             java.util.List<GearOffer> gear, boolean open) implements CustomPayload {
        public static final Id<MarketView> ID = id("market");
        public static final PacketCodec<RegistryByteBuf, MarketView> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.town); b.writeString(v.sector); b.writeString(v.controller); b.writeVarInt(v.discount);
            b.writeVarInt(v.goods.size());
            for (MarketGood g : v.goods) g.write(b);
            b.writeVarInt(v.gear.size());
            for (GearOffer g : v.gear) { b.writeVarInt(g.slot()); b.writeVarLong(g.value()); }
            b.writeBoolean(v.open);
        }, b -> {
            String t = b.readString(), s = b.readString(), c = b.readString();
            int d = b.readVarInt();
            int n = Math.min(b.readVarInt(), 512);
            java.util.List<MarketGood> g = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) g.add(MarketGood.read(b));
            int m = Math.min(b.readVarInt(), 64);
            java.util.List<GearOffer> gear = new java.util.ArrayList<>();
            for (int i = 0; i < m; i++) gear.add(new GearOffer(b.readVarInt(), b.readVarLong()));
            return new MarketView(t, s, c, d, g, gear, b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** A Global Market listing: buy-now price (0 none), auction start, top bid and bidder, bids, seconds left, lowest next bid. */
    public record ExchangeEntry(long id, net.minecraft.item.ItemStack item, long price, String seller, boolean mine,
                                long startBid, long bid, int bids, boolean leading, String bidder, long endsIn, long minBid) { }

    /** Server -> client: the Exchange listings. */
    public record ExchangeView(java.util.List<ExchangeEntry> list) implements CustomPayload {
        public static final Id<ExchangeView> ID = id("exchange");
        public static final PacketCodec<RegistryByteBuf, ExchangeView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.list.size());
            for (ExchangeEntry e : v.list) {
                b.writeVarLong(e.id()); net.minecraft.item.ItemStack.OPTIONAL_PACKET_CODEC.encode(b, e.item());
                b.writeVarLong(e.price()); b.writeString(e.seller()); b.writeBoolean(e.mine());
                b.writeVarLong(e.startBid()); b.writeVarLong(e.bid()); b.writeVarInt(e.bids()); b.writeBoolean(e.leading());
                b.writeString(e.bidder()); b.writeVarLong(e.endsIn()); b.writeVarLong(e.minBid());
            }
        }, b -> {
            int n = Math.min(b.readVarInt(), 256);
            java.util.List<ExchangeEntry> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                l.add(new ExchangeEntry(b.readVarLong(), net.minecraft.item.ItemStack.OPTIONAL_PACKET_CODEC.decode(b), b.readVarLong(),
                    b.readString(), b.readBoolean(), b.readVarLong(), b.readVarLong(), b.readVarInt(), b.readBoolean(), b.readString(),
                    b.readVarLong(), b.readVarLong()));
            }
            return new ExchangeView(l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: buy/sell/sellgear/list/buylisting/cancel/exchange/open with item, amount, number. */
    public record MarketAction(String action, String item, int qty, long number) implements CustomPayload {
        public static final Id<MarketAction> ID = id("market_action");
        public static final PacketCodec<RegistryByteBuf, MarketAction> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.action); b.writeString(v.item); b.writeVarInt(v.qty); b.writeVarLong(v.number);
        }, b -> new MarketAction(b.readString(), b.readString(), b.readVarInt(), b.readVarLong()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record SectorInfo(String title, float[] influence, int controller) { }

    public record OrderInfo(String id, int sector, String text, long marks, int rep, int progress, int qty) { }

    /** Server -> client: your faction, the sectors and this cycle's work orders. */
    public record FactionView(int faction, int rep, int here, java.util.List<SectorInfo> sectors, java.util.List<OrderInfo> orders,
                              long cycleLeft, long[] treasury, boolean open, int[] walls, int[] held, int leader, float[] mult,
                              String event, long eventLeft, java.util.List<String> eventTop, int myKills, long nextEvent) implements CustomPayload {
        public static final Id<FactionView> ID = id("factions");
        public static final PacketCodec<RegistryByteBuf, FactionView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.faction + 1); b.writeVarInt(v.rep); b.writeVarInt(v.here);
            b.writeVarInt(v.sectors.size());
            for (SectorInfo s : v.sectors) {
                b.writeString(s.title()); b.writeVarInt(s.influence().length);
                for (float f : s.influence()) b.writeFloat(f);
                b.writeVarInt(s.controller() + 1);
            }
            b.writeVarInt(v.orders.size());
            for (OrderInfo o : v.orders) {
                b.writeString(o.id()); b.writeVarInt(o.sector()); b.writeString(o.text()); b.writeVarLong(o.marks());
                b.writeVarInt(o.rep()); b.writeVarInt(o.progress() + 2); b.writeVarInt(o.qty());
            }
            b.writeVarLong(v.cycleLeft);
            b.writeVarInt(v.treasury.length);
            for (long t : v.treasury) b.writeVarLong(t);
            b.writeBoolean(v.open);
            b.writeIntArray(v.walls); b.writeIntArray(v.held); b.writeVarInt(v.leader + 1);
            b.writeVarInt(v.mult.length);
            for (float x : v.mult) b.writeFloat(x);
            b.writeString(v.event); b.writeVarLong(v.eventLeft);
            b.writeVarInt(v.eventTop.size());
            for (String s : v.eventTop) b.writeString(s);
            b.writeVarInt(v.myKills); b.writeVarLong(v.nextEvent);
        }, b -> {
            int f = b.readVarInt() - 1, rep = b.readVarInt(), here = b.readVarInt();
            int n = Math.min(b.readVarInt(), 16);
            java.util.List<SectorInfo> sec = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String t = b.readString();
                float[] inf = new float[Math.min(b.readVarInt(), 8)];
                for (int k = 0; k < inf.length; k++) inf[k] = b.readFloat();
                sec.add(new SectorInfo(t, inf, b.readVarInt() - 1));
            }
            int m = Math.min(b.readVarInt(), 64);
            java.util.List<OrderInfo> ord = new java.util.ArrayList<>();
            for (int i = 0; i < m; i++) {
                ord.add(new OrderInfo(b.readString(), b.readVarInt(), b.readString(), b.readVarLong(), b.readVarInt(), b.readVarInt() - 2, b.readVarInt()));
            }
            long left = b.readVarLong();
            long[] tr = new long[Math.min(b.readVarInt(), 8)];
            for (int k = 0; k < tr.length; k++) tr[k] = b.readVarLong();
            boolean open = b.readBoolean();
            int[] walls = b.readIntArray(8), held = b.readIntArray(8);
            int leader = b.readVarInt() - 1;
            float[] mult = new float[Math.min(b.readVarInt(), 8)];
            for (int k = 0; k < mult.length; k++) mult[k] = b.readFloat();
            String ev = b.readString();
            long evLeft = b.readVarLong();
            int tn = Math.min(b.readVarInt(), 16);
            java.util.List<String> top = new java.util.ArrayList<>();
            for (int i = 0; i < tn; i++) top.add(b.readString());
            return new FactionView(f, rep, here, sec, ord, left, tr, open, walls, held, leader, mult, ev, evLeft, top, b.readVarInt(), b.readVarLong());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: join / leave / accept / turnin / abandon / open. */
    public record FactionAction(String action, String arg) implements CustomPayload {
        public static final Id<FactionAction> ID = id("faction_action");
        public static final PacketCodec<RegistryByteBuf, FactionAction> CODEC =
            PacketCodec.of((v, b) -> { b.writeString(v.action); b.writeString(v.arg); }, b -> new FactionAction(b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record HomeUpgrade(String id, String title, String desc, int price, boolean owned) { }

    /** Server -> client: a house deed (to buy) or your home (upgrades). */
    public record HomeView(int home, String town, String size, long price, boolean owned, int homes, int maxHomes,
                           java.util.List<HomeUpgrade> upgrades, java.util.List<String> visits, boolean open,
                           String kind, long offer, String owner) implements CustomPayload {
        public static final Id<HomeView> ID = id("home");
        public static final PacketCodec<RegistryByteBuf, HomeView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.home); b.writeString(v.town); b.writeString(v.size); b.writeVarLong(v.price); b.writeBoolean(v.owned);
            b.writeVarInt(v.homes); b.writeVarInt(v.maxHomes);
            b.writeVarInt(v.upgrades.size());
            for (HomeUpgrade u : v.upgrades) { b.writeString(u.id()); b.writeString(u.title()); b.writeString(u.desc()); b.writeVarInt(u.price()); b.writeBoolean(u.owned()); }
            b.writeVarInt(v.visits.size());
            for (String s : v.visits) b.writeString(s);
            b.writeBoolean(v.open);
            b.writeString(v.kind); b.writeVarLong(v.offer + 1); b.writeString(v.owner);
        }, b -> {
            int home = b.readVarInt();
            String town = b.readString(), size = b.readString();
            long price = b.readVarLong();
            boolean owned = b.readBoolean();
            int homes = b.readVarInt(), max = b.readVarInt();
            int n = Math.min(b.readVarInt(), 32);
            java.util.List<HomeUpgrade> ups = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) ups.add(new HomeUpgrade(b.readString(), b.readString(), b.readString(), b.readVarInt(), b.readBoolean()));
            int m = Math.min(b.readVarInt(), 16);
            java.util.List<String> visits = new java.util.ArrayList<>();
            for (int i = 0; i < m; i++) visits.add(b.readString());
            boolean open = b.readBoolean();
            return new HomeView(home, town, size, price, owned, homes, max, ups, visits, open, b.readString(), b.readVarLong() - 1, b.readString());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record AdminRow(int index, String label, String size, long price, String owners) { }

    /** Server -> client: the operator's property list (one page). */
    public record HomeAdminView(java.util.List<AdminRow> rows, int page, int pages, int total, java.util.List<String> players, boolean open)
            implements CustomPayload {
        public static final Id<HomeAdminView> ID = id("home_admin");
        public static final PacketCodec<RegistryByteBuf, HomeAdminView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.rows.size());
            for (AdminRow r : v.rows) { b.writeVarInt(r.index()); b.writeString(r.label()); b.writeString(r.size()); b.writeVarLong(r.price()); b.writeString(r.owners()); }
            b.writeVarInt(v.page); b.writeVarInt(v.pages); b.writeVarInt(v.total);
            b.writeVarInt(v.players.size());
            for (String s : v.players) b.writeString(s);
            b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 200);
            java.util.List<AdminRow> rows = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) rows.add(new AdminRow(b.readVarInt(), b.readString(), b.readString(), b.readVarLong(), b.readString()));
            int page = b.readVarInt(), pages = b.readVarInt(), total = b.readVarInt();
            int m = Math.min(b.readVarInt(), 200);
            java.util.List<String> players = new java.util.ArrayList<>();
            for (int i = 0; i < m; i++) players.add(b.readString());
            return new HomeAdminView(rows, page, pages, total, players, b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: buy / enter / visit / upgrade / sell / manage / offers / acceptoffer / admin_*. */
    public record HomeAction(String action, int home, String arg) implements CustomPayload {
        public static final Id<HomeAction> ID = id("home_action");
        public static final PacketCodec<RegistryByteBuf, HomeAction> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.action); b.writeVarInt(v.home); b.writeString(v.arg);
        }, b -> new HomeAction(b.readString(), b.readVarInt(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record ForgeGear(int slot, int up, long marks, int iron, int steel, float chance) { }

    public record ForgeRecipe(String id, String title, String materials, long marks, boolean ready) { }

    /** Server -> client: the forge: your gear with upgrade costs, and recipes. */
    public record ForgeView(java.util.List<ForgeGear> gear, java.util.List<ForgeRecipe> recipes, int smithing, int iron, int steel, boolean open)
            implements CustomPayload {
        public static final Id<ForgeView> ID = id("forge");
        public static final PacketCodec<RegistryByteBuf, ForgeView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.gear.size());
            for (ForgeGear g : v.gear) { b.writeVarInt(g.slot()); b.writeVarInt(g.up()); b.writeVarLong(g.marks()); b.writeVarInt(g.iron()); b.writeVarInt(g.steel()); b.writeFloat(g.chance()); }
            b.writeVarInt(v.recipes.size());
            for (ForgeRecipe r : v.recipes) { b.writeString(r.id()); b.writeString(r.title()); b.writeString(r.materials()); b.writeVarLong(r.marks()); b.writeBoolean(r.ready()); }
            b.writeVarInt(v.smithing); b.writeVarInt(v.iron); b.writeVarInt(v.steel); b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 64);
            java.util.List<ForgeGear> g = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) g.add(new ForgeGear(b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readFloat()));
            int m = Math.min(b.readVarInt(), 64);
            java.util.List<ForgeRecipe> r = new java.util.ArrayList<>();
            for (int i = 0; i < m; i++) r.add(new ForgeRecipe(b.readString(), b.readString(), b.readString(), b.readVarLong(), b.readBoolean()));
            return new ForgeView(g, r, b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: forge upgrade (slot) or craft (recipe), with the minigame quality 0..1. */
    public record ForgeAction(String action, int slot, String recipe, float quality) implements CustomPayload {
        public static final Id<ForgeAction> ID = id("forge_action");
        public static final PacketCodec<RegistryByteBuf, ForgeAction> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.action); b.writeVarInt(v.slot); b.writeString(v.recipe); b.writeFloat(v.quality);
        }, b -> new ForgeAction(b.readString(), b.readVarInt(), b.readString(), b.readFloat()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record ModeEntry(String id, String title, String desc, boolean unlocked, boolean active, String requirement) { }

    // ------------------------------------------------------------------ battle pass, event shop, social hub

    public record PassTier(String free, String freeIcon, int freeColor, boolean freeClaimed,
                           String premium, String premiumIcon, int premiumColor, boolean premiumClaimed) { }

    /** Server -> client: the season track and this account's progress. */
    public record PassView(String name, long endsAt, long xp, long xpPerTier, boolean premium, long premiumGold, boolean ended,
                           java.util.List<PassTier> tiers, boolean open) implements CustomPayload {
        public static final Id<PassView> ID = id("pass");
        public static final PacketCodec<RegistryByteBuf, PassView> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.name); b.writeLong(v.endsAt); b.writeVarLong(v.xp); b.writeVarLong(v.xpPerTier); b.writeBoolean(v.premium);
            b.writeVarLong(v.premiumGold); b.writeBoolean(v.ended);
            b.writeVarInt(v.tiers.size());
            for (PassTier t : v.tiers) {
                b.writeString(t.free()); b.writeString(t.freeIcon()); b.writeInt(t.freeColor()); b.writeBoolean(t.freeClaimed());
                b.writeString(t.premium()); b.writeString(t.premiumIcon()); b.writeInt(t.premiumColor()); b.writeBoolean(t.premiumClaimed());
            }
            b.writeBoolean(v.open);
        }, b -> {
            String name = b.readString();
            long ends = b.readLong(), xp = b.readVarLong(), per = b.readVarLong();
            boolean prem = b.readBoolean();
            long gold = b.readVarLong();
            boolean ended = b.readBoolean();
            int n = Math.min(b.readVarInt(), 200);
            java.util.List<PassTier> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new PassTier(b.readString(), b.readString(), b.readInt(), b.readBoolean(),
                b.readString(), b.readString(), b.readInt(), b.readBoolean()));
            return new PassView(name, ends, xp, per, prem, gold, ended, l, b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open, claim (tier), claimall, buy. */
    public record PassAction(String action, int tier) implements CustomPayload {
        public static final Id<PassAction> ID = id("pass_action");
        public static final PacketCodec<RegistryByteBuf, PassAction> CODEC =
            PacketCodec.of((v, b) -> { b.writeString(v.action); b.writeVarInt(v.tier); }, b -> new PassAction(b.readString(), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record EventItem(String key, String title, String desc, String icon, int color, long tokens, long gold, int limit, int bought) { }

    /** Server -> client: the special event shop. */
    public record EventView(boolean running, String name, String token, long endsAt, long tokens, java.util.List<EventItem> items,
                            boolean open) implements CustomPayload {
        public static final Id<EventView> ID = id("event");
        public static final PacketCodec<RegistryByteBuf, EventView> CODEC = PacketCodec.of((v, b) -> {
            b.writeBoolean(v.running); b.writeString(v.name); b.writeString(v.token); b.writeLong(v.endsAt); b.writeVarLong(v.tokens);
            b.writeVarInt(v.items.size());
            for (EventItem i : v.items) {
                b.writeString(i.key()); b.writeString(i.title()); b.writeString(i.desc()); b.writeString(i.icon()); b.writeInt(i.color());
                b.writeVarLong(i.tokens()); b.writeVarLong(i.gold()); b.writeVarInt(i.limit()); b.writeVarInt(i.bought());
            }
            b.writeBoolean(v.open);
        }, b -> {
            boolean run = b.readBoolean();
            String name = b.readString(), token = b.readString();
            long ends = b.readLong(), tokens = b.readVarLong();
            int n = Math.min(b.readVarInt(), 200);
            java.util.List<EventItem> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new EventItem(b.readString(), b.readString(), b.readString(), b.readString(), b.readInt(),
                b.readVarLong(), b.readVarLong(), b.readVarInt(), b.readVarInt()));
            return new EventView(run, name, token, ends, tokens, l, b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open, buy (tokens), buy_gold. */
    public record EventAction(String action, String item) implements CustomPayload {
        public static final Id<EventAction> ID = id("event_action");
        public static final PacketCodec<RegistryByteBuf, EventAction> CODEC =
            PacketCodec.of((v, b) -> { b.writeString(v.action); b.writeString(v.item); }, b -> new EventAction(b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record SocialPlayer(java.util.UUID uuid, String name, String account, int level, String tag, int tagColor, boolean online,
                               boolean friend, boolean party, String where, String faction, int factionColor) { }

    /** Server -> client: the social hub list. */
    public record SocialView(java.util.List<SocialPlayer> players, boolean open) implements CustomPayload {
        public static final Id<SocialView> ID = id("social");
        public static final PacketCodec<RegistryByteBuf, SocialView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.players.size());
            for (SocialPlayer s : v.players) {
                b.writeUuid(s.uuid()); b.writeString(s.name()); b.writeString(s.account()); b.writeVarInt(s.level()); b.writeString(s.tag());
                b.writeInt(s.tagColor()); b.writeBoolean(s.online()); b.writeBoolean(s.friend()); b.writeBoolean(s.party());
                b.writeString(s.where()); b.writeString(s.faction()); b.writeInt(s.factionColor());
            }
            b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 1000);
            java.util.List<SocialPlayer> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new SocialPlayer(b.readUuid(), b.readString(), b.readString(), b.readVarInt(), b.readString(),
                b.readInt(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readString(), b.readString(), b.readInt()));
            return new SocialView(l, b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open, friend_add, friend_remove, invite. */
    public record SocialAction(String action, java.util.UUID target) implements CustomPayload {
        public static final Id<SocialAction> ID = id("social_action");
        public static final PacketCodec<RegistryByteBuf, SocialAction> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.action);
            b.writeBoolean(v.target != null);
            if (v.target != null) b.writeUuid(v.target);
        }, b -> new SocialAction(b.readString(), b.readBoolean() ? b.readUuid() : null));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: the player stepped onto ("Your home" / "Your property") or off ("") their property. */
    public record PropertyState(String where) implements CustomPayload {
        public static final Id<PropertyState> ID = id("property");
        public static final PacketCodec<RegistryByteBuf, PropertyState> CODEC =
            PacketCodec.of((v, b) -> b.writeString(v.where), b -> new PropertyState(b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** A furniture piece: footprint (x0..x1, 0..y1, z0..z1 as seen looking north) and how many are in the crate. */
    public record FurniturePiece(String id, String title, String category, long price, int owned, String icon,
                                 int x0, int x1, int y1, int z0, int z1) { }

    /** Server -> client: the furniture store and the character's crate. */
    public record FurnitureView(java.util.List<FurniturePiece> pieces, boolean onProperty, boolean open) implements CustomPayload {
        public static final Id<FurnitureView> ID = id("furniture");
        public static final PacketCodec<RegistryByteBuf, FurnitureView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.pieces.size());
            for (FurniturePiece f : v.pieces) {
                b.writeString(f.id()); b.writeString(f.title()); b.writeString(f.category()); b.writeVarLong(f.price()); b.writeVarInt(f.owned());
                b.writeString(f.icon()); b.writeVarInt(f.x0()); b.writeVarInt(f.x1()); b.writeVarInt(f.y1()); b.writeVarInt(f.z0()); b.writeVarInt(f.z1());
            }
            b.writeBoolean(v.onProperty); b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 500);
            java.util.List<FurniturePiece> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new FurniturePiece(b.readString(), b.readString(), b.readString(), b.readVarLong(), b.readVarInt(),
                b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
            return new FurnitureView(l, b.readBoolean(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open, buy (piece), place (piece at a block). */
    public record FurnitureAction(String action, String piece, long at) implements CustomPayload {
        public static final Id<FurnitureAction> ID = id("furniture_action");
        public static final PacketCodec<RegistryByteBuf, FurnitureAction> CODEC = PacketCodec.of(
            (v, b) -> { b.writeString(v.action); b.writeString(v.piece); b.writeLong(v.at); },
            b -> new FurnitureAction(b.readString(), b.readString(), b.readLong()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** A task on a board: period 0 daily, 1 weekly, 2 monthly, 3 season. */
    public record TaskEntry(String id, int period, String text, int progress, int goal, String reward, String icon, boolean claimed) { }

    public record AchievementEntry(String id, String title, String desc, long progress, long goal, String reward, int color, boolean earned) { }

    /** Server -> client: task boards, achievements, the worn title and seconds left per period. */
    public record TasksView(java.util.List<TaskEntry> tasks, java.util.List<AchievementEntry> achievements, String title, long[] left,
                            boolean open) implements CustomPayload {
        public static final Id<TasksView> ID = id("tasks");
        public static final PacketCodec<RegistryByteBuf, TasksView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.tasks.size());
            for (TaskEntry t : v.tasks) {
                b.writeString(t.id()); b.writeVarInt(t.period()); b.writeString(t.text()); b.writeVarInt(t.progress()); b.writeVarInt(t.goal());
                b.writeString(t.reward()); b.writeString(t.icon()); b.writeBoolean(t.claimed());
            }
            b.writeVarInt(v.achievements.size());
            for (AchievementEntry a : v.achievements) {
                b.writeString(a.id()); b.writeString(a.title()); b.writeString(a.desc()); b.writeVarLong(a.progress()); b.writeVarLong(a.goal());
                b.writeString(a.reward()); b.writeInt(a.color()); b.writeBoolean(a.earned());
            }
            b.writeString(v.title);
            b.writeVarInt(v.left.length);
            for (long l : v.left) b.writeLong(l);
            b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 100);
            java.util.List<TaskEntry> t = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) t.add(new TaskEntry(b.readString(), b.readVarInt(), b.readString(), b.readVarInt(), b.readVarInt(),
                b.readString(), b.readString(), b.readBoolean()));
            int m = Math.min(b.readVarInt(), 200);
            java.util.List<AchievementEntry> a = new java.util.ArrayList<>();
            for (int i = 0; i < m; i++) a.add(new AchievementEntry(b.readString(), b.readString(), b.readString(), b.readVarLong(), b.readVarLong(),
                b.readString(), b.readInt(), b.readBoolean()));
            String title = b.readString();
            int k = Math.min(b.readVarInt(), 8);
            long[] left = new long[k];
            for (int i = 0; i < k; i++) left[i] = b.readLong();
            return new TasksView(t, a, title, left, b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open, claim (task id), claimall, title (achievement id or ""). */
    public record TaskAction(String action, String arg) implements CustomPayload {
        public static final Id<TaskAction> ID = id("task_action");
        public static final PacketCodec<RegistryByteBuf, TaskAction> CODEC =
            PacketCodec.of((v, b) -> { b.writeString(v.action); b.writeString(v.arg); }, b -> new TaskAction(b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: the guard (block) key held or released. */
    public record GuardKey(boolean on) implements CustomPayload {
        public static final Id<GuardKey> ID = id("guard_key");
        public static final PacketCodec<RegistryByteBuf, GuardKey> CODEC =
            PacketCodec.of((v, b) -> b.writeBoolean(v.on), b -> new GuardKey(b.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> nearby clients: a block (0), clash (1) or broken guard (2) at a point, in someone's cosmetic style. */
    public record GuardFx(int kind, double x, double y, double z, String style) implements CustomPayload {
        public static final Id<GuardFx> ID = id("guard_fx");
        public static final PacketCodec<RegistryByteBuf, GuardFx> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.kind); b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z); b.writeString(v.style);
        }, b -> new GuardFx(b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: a slide-in notification (key: an update to an earlier one with the same key). */
    public record Toast(net.minecraft.text.Text title, net.minecraft.text.Text sub, int color, String icon, String key) implements CustomPayload {
        public static final Id<Toast> ID = id("toast");
        public static final PacketCodec<RegistryByteBuf, Toast> CODEC = PacketCodec.of((v, b) -> {
            net.minecraft.text.TextCodecs.REGISTRY_PACKET_CODEC.encode(b, v.title);
            net.minecraft.text.TextCodecs.REGISTRY_PACKET_CODEC.encode(b, v.sub);
            b.writeInt(v.color); b.writeString(v.icon); b.writeString(v.key);
        }, b -> new Toast(net.minecraft.text.TextCodecs.REGISTRY_PACKET_CODEC.decode(b), net.minecraft.text.TextCodecs.REGISTRY_PACKET_CODEC.decode(b),
            b.readInt(), b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: reset the skill trees (limited per character, first one free). */
    public record SkillReset() implements CustomPayload {
        public static final Id<SkillReset> ID = id("skill_reset");
        public static final PacketCodec<RegistryByteBuf, SkillReset> CODEC = PacketCodec.unit(new SkillReset());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record BreedEntry(String id, String title, String blurb, float speedMin, float speedMax, float jumpMin, float jumpMax,
                             int hpMin, int hpMax, int cap, long price) { }

    public record HorseEntry(String id, String name, String breed, int level, int cap, int xp, int xpNext, float speed, float jump,
                             float health, boolean saddle, String armor, int color, boolean active, boolean out, boolean lent, boolean resting) { }

    /** Server -> client: the stable screen (mode master / home / horse). */
    public record StableView(String mode, java.util.List<BreedEntry> breeds, java.util.List<HorseEntry> horses, int capacity, int quest,
                             float questDist, float questGoal, boolean atStable, boolean open) implements CustomPayload {
        public static final Id<StableView> ID = id("stable");
        public static final PacketCodec<RegistryByteBuf, StableView> CODEC = PacketCodec.of((v, b) -> {
            b.writeString(v.mode);
            b.writeVarInt(v.breeds.size());
            for (BreedEntry e : v.breeds) {
                b.writeString(e.id()); b.writeString(e.title()); b.writeString(e.blurb()); b.writeFloat(e.speedMin()); b.writeFloat(e.speedMax());
                b.writeFloat(e.jumpMin()); b.writeFloat(e.jumpMax()); b.writeVarInt(e.hpMin()); b.writeVarInt(e.hpMax()); b.writeVarInt(e.cap());
                b.writeVarLong(e.price());
            }
            b.writeVarInt(v.horses.size());
            for (HorseEntry h : v.horses) {
                b.writeString(h.id()); b.writeString(h.name()); b.writeString(h.breed()); b.writeVarInt(h.level()); b.writeVarInt(h.cap());
                b.writeVarInt(h.xp()); b.writeVarInt(h.xpNext()); b.writeFloat(h.speed()); b.writeFloat(h.jump()); b.writeFloat(h.health());
                b.writeBoolean(h.saddle()); b.writeString(h.armor()); b.writeVarInt(h.color()); b.writeBoolean(h.active()); b.writeBoolean(h.out());
                b.writeBoolean(h.lent()); b.writeBoolean(h.resting());
            }
            b.writeVarInt(v.capacity); b.writeVarInt(v.quest); b.writeFloat(v.questDist); b.writeFloat(v.questGoal);
            b.writeBoolean(v.atStable); b.writeBoolean(v.open);
        }, b -> {
            String mode = b.readString();
            int n = Math.min(b.readVarInt(), 64);
            java.util.List<BreedEntry> br = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) br.add(new BreedEntry(b.readString(), b.readString(), b.readString(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readFloat(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarLong()));
            int m = Math.min(b.readVarInt(), 128);
            java.util.List<HorseEntry> hs = new java.util.ArrayList<>();
            for (int i = 0; i < m; i++) hs.add(new HorseEntry(b.readString(), b.readString(), b.readString(), b.readVarInt(), b.readVarInt(),
                b.readVarInt(), b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat(), b.readBoolean(), b.readString(), b.readVarInt(),
                b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean()));
            return new StableView(mode, br, hs, b.readVarInt(), b.readVarInt(), b.readFloat(), b.readFloat(), b.readBoolean(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: quest, buy (arg breed, id name), select, rename, saddle, armor, dismiss, call, release, close. */
    public record StableAction(String action, String horse, String arg) implements CustomPayload {
        public static final Id<StableAction> ID = id("stable_action");
        public static final PacketCodec<RegistryByteBuf, StableAction> CODEC = PacketCodec.of(
            (v, b) -> { b.writeString(v.action); b.writeString(v.horse); b.writeString(v.arg); },
            b -> new StableAction(b.readString(), b.readString(), b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: the game modes and which are unlocked. */
    public record ModeView(java.util.List<ModeEntry> modes, int chapter, boolean open) implements CustomPayload {
        public static final Id<ModeView> ID = id("modes");
        public static final PacketCodec<RegistryByteBuf, ModeView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.modes.size());
            for (ModeEntry m : v.modes) {
                b.writeString(m.id()); b.writeString(m.title()); b.writeString(m.desc()); b.writeBoolean(m.unlocked()); b.writeBoolean(m.active()); b.writeString(m.requirement());
            }
            b.writeVarInt(v.chapter); b.writeBoolean(v.open);
        }, b -> {
            int n = Math.min(b.readVarInt(), 32);
            java.util.List<ModeEntry> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new ModeEntry(b.readString(), b.readString(), b.readString(), b.readBoolean(), b.readBoolean(), b.readString()));
            return new ModeView(l, b.readVarInt(), b.readBoolean());
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** One downed player: entity id, bleed seconds left and max, revive progress 0..1, pressing wounds, who is reviving. */
    public record DownedEntry(int entity, float left, float max, float revive, boolean pressing, String reviver) { }

    /** Server -> client: every downed player right now. */
    public record DownedView(java.util.List<DownedEntry> list) implements CustomPayload {
        public static final Id<DownedView> ID = id("downed");
        public static final PacketCodec<RegistryByteBuf, DownedView> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.list.size());
            for (DownedEntry e : v.list) {
                b.writeVarInt(e.entity()); b.writeFloat(e.left()); b.writeFloat(e.max()); b.writeFloat(e.revive()); b.writeBoolean(e.pressing()); b.writeString(e.reviver());
            }
        }, b -> {
            int n = Math.min(b.readVarInt(), 128);
            java.util.List<DownedEntry> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new DownedEntry(b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat(), b.readBoolean(), b.readString()));
            return new DownedView(l);
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client -> server: open, or choose a mode by id. */
    public record ModeAction(String mode) implements CustomPayload {
        public static final Id<ModeAction> ID = id("mode_action");
        public static final PacketCodec<RegistryByteBuf, ModeAction> CODEC = PacketCodec.of((v, b) -> b.writeString(v.mode), b -> new ModeAction(b.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    static void register() {
        PayloadTypeRegistry.playS2C().register(ModeView.ID, ModeView.CODEC);
        PayloadTypeRegistry.playS2C().register(DownedView.ID, DownedView.CODEC);
        PayloadTypeRegistry.playC2S().register(ModeAction.ID, ModeAction.CODEC);
        PayloadTypeRegistry.playS2C().register(PassView.ID, PassView.CODEC);
        PayloadTypeRegistry.playS2C().register(StableView.ID, StableView.CODEC);
        PayloadTypeRegistry.playC2S().register(StableAction.ID, StableAction.CODEC);
        PayloadTypeRegistry.playC2S().register(SlashHit.ID, SlashHit.CODEC);
        PayloadTypeRegistry.playC2S().register(SkillReset.ID, SkillReset.CODEC);
        PayloadTypeRegistry.playS2C().register(Toast.ID, Toast.CODEC);
        PayloadTypeRegistry.playS2C().register(CosmeticsOf.ID, CosmeticsOf.CODEC);
        PayloadTypeRegistry.playS2C().register(SlashFx.ID, SlashFx.CODEC);
        PayloadTypeRegistry.playC2S().register(GuardKey.ID, GuardKey.CODEC);
        PayloadTypeRegistry.playS2C().register(GuardFx.ID, GuardFx.CODEC);
        PayloadTypeRegistry.playS2C().register(TasksView.ID, TasksView.CODEC);
        PayloadTypeRegistry.playC2S().register(TaskAction.ID, TaskAction.CODEC);
        PayloadTypeRegistry.playS2C().register(PropertyState.ID, PropertyState.CODEC);
        PayloadTypeRegistry.playS2C().register(FurnitureView.ID, FurnitureView.CODEC);
        PayloadTypeRegistry.playC2S().register(FurnitureAction.ID, FurnitureAction.CODEC);
        PayloadTypeRegistry.playC2S().register(PassAction.ID, PassAction.CODEC);
        PayloadTypeRegistry.playS2C().register(EventView.ID, EventView.CODEC);
        PayloadTypeRegistry.playC2S().register(EventAction.ID, EventAction.CODEC);
        PayloadTypeRegistry.playS2C().register(SocialView.ID, SocialView.CODEC);
        PayloadTypeRegistry.playC2S().register(SocialAction.ID, SocialAction.CODEC);
        PayloadTypeRegistry.playS2C().register(ForgeView.ID, ForgeView.CODEC);
        PayloadTypeRegistry.playC2S().register(ForgeAction.ID, ForgeAction.CODEC);
        PayloadTypeRegistry.playS2C().register(HomeView.ID, HomeView.CODEC);
        PayloadTypeRegistry.playS2C().register(HomeAdminView.ID, HomeAdminView.CODEC);
        PayloadTypeRegistry.playC2S().register(HomeAction.ID, HomeAction.CODEC);
        PayloadTypeRegistry.playS2C().register(MarketView.ID, MarketView.CODEC);
        PayloadTypeRegistry.playS2C().register(ExchangeView.ID, ExchangeView.CODEC);
        PayloadTypeRegistry.playC2S().register(MarketAction.ID, MarketAction.CODEC);
        PayloadTypeRegistry.playS2C().register(FactionView.ID, FactionView.CODEC);
        PayloadTypeRegistry.playC2S().register(FactionAction.ID, FactionAction.CODEC);
        PayloadTypeRegistry.playS2C().register(HitMarker.ID, HitMarker.CODEC);
        PayloadTypeRegistry.playS2C().register(WalletSync.ID, WalletSync.CODEC);
        PayloadTypeRegistry.playS2C().register(CharacterList.ID, CharacterList.CODEC);
        PayloadTypeRegistry.playC2S().register(CharacterAction.ID, CharacterAction.CODEC);
        PayloadTypeRegistry.playC2S().register(Struggle.ID, Struggle.CODEC);
        PayloadTypeRegistry.playC2S().register(FishResult.ID, FishResult.CODEC);
        PayloadTypeRegistry.playS2C().register(FishBite.ID, FishBite.CODEC);
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
        PayloadTypeRegistry.playC2S().register(StatsRequest.ID, StatsRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(StatsView.ID, StatsView.CODEC);
        PayloadTypeRegistry.playS2C().register(TitanTags.ID, TitanTags.CODEC);
        PayloadTypeRegistry.playS2C().register(EstateView.ID, EstateView.CODEC);
        PayloadTypeRegistry.playC2S().register(EstateAction.ID, EstateAction.CODEC);
        PayloadTypeRegistry.playS2C().register(RaidView.ID, RaidView.CODEC);
        PayloadTypeRegistry.playC2S().register(RaidAction.ID, RaidAction.CODEC);
        PayloadTypeRegistry.playS2C().register(RegimentView.ID, RegimentView.CODEC);
        PayloadTypeRegistry.playC2S().register(RegimentAction.ID, RegimentAction.CODEC);
        PayloadTypeRegistry.playS2C().register(BagView.ID, BagView.CODEC);
        PayloadTypeRegistry.playC2S().register(BagAction.ID, BagAction.CODEC);
        PayloadTypeRegistry.playS2C().register(NapeHit.ID, NapeHit.CODEC);
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
        PayloadTypeRegistry.playC2S().register(UseAbility.ID, UseAbility.CODEC);
        PayloadTypeRegistry.playC2S().register(ChooseRole.ID, ChooseRole.CODEC);
        PayloadTypeRegistry.playS2C().register(ClassHud.ID, ClassHud.CODEC);
    }
}
