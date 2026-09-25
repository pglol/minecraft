package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.HorseColor;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Horses you own. Every character keeps a stable of horses (name, breed, level and stats); one is
 * the ride-out horse you call with the whistle or the call key (N), the rest live in your home
 * stables. The first horse comes from the Stable Master's quest, saddle included; later horses
 * are bought from Stable Masters (the breeds depend on the region) and need a saddle and stable
 * space at home. Horses level up as you ride them, up to their breed's cap. Swap your ride-out
 * horse and rename horses at a Stable Master or at your home stables.
 */
public final class Horses {
    public record Breed(String id, String title, String blurb, double speedMin, double speedMax, double jumpMin, double jumpMax,
                        double hpMin, double hpMax, int cap, long price, List<Sector> sectors) { }

    public static final List<Breed> BREEDS = List.of(
        new Breed("farm", "Farm Horse", "Steady and cheap. Fine for errands.", 0.17, 0.21, 0.50, 0.65, 16, 20, 10, 500,
            List.of(Sector.MARIA, Sector.ROSE, Sector.SINA, Sector.BEYOND)),
        new Breed("pony", "Shiganshina Pony", "Small, hardy and loyal.", 0.19, 0.23, 0.55, 0.70, 22, 28, 15, 900,
            List.of(Sector.MARIA)),
        new Breed("courser", "Swift Courser", "Light and quick on open roads.", 0.22, 0.27, 0.60, 0.75, 18, 24, 20, 1800,
            List.of(Sector.MARIA, Sector.ROSE, Sector.SINA)),
        new Breed("warhorse", "Survey Corps Warhorse", "Bred for expeditions: fast, brave, enduring.", 0.26, 0.31, 0.70, 0.90, 24, 30, 30, 4500,
            List.of(Sector.ROSE, Sector.SINA)),
        new Breed("mustang", "Paradis Mustang", "Wild-caught. Anything from middling to magnificent.", 0.22, 0.36, 0.60, 1.00, 18, 30, 35, 6000,
            List.of(Sector.BEYOND, Sector.MARIA)),
        new Breed("thoroughbred", "Karanes Thoroughbred", "A racing line from the eastern districts.", 0.29, 0.34, 0.75, 0.95, 22, 28, 40, 9000,
            List.of(Sector.ROSE)),
        new Breed("destrier", "Marleyan Destrier", "Heavy cavalry stock. Tough as iron.", 0.28, 0.33, 0.70, 0.90, 30, 36, 45, 15000,
            List.of(Sector.MARLEY)),
        new Breed("charger", "Royal Charger", "The King's own bloodline, from Mitras.", 0.32, 0.37, 0.80, 1.00, 26, 32, 50, 20000,
            List.of(Sector.SINA)));

    public static final long SADDLE = 350, ARMOR_IRON = 800, ARMOR_GOLD = 1500, ARMOR_DIAMOND = 3500;
    public static final int STABLE_SLOTS = 4;
    private static final double QUEST_DIST = 300;

    /** One horse. */
    public static final class Horse {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String breed = "farm";
        public int level = 1, cap = 10, color;
        public long xp;
        public double speed, jump, health;
        public boolean saddle;
        public String armor = "";
        /** Lent by the Stable Master until the first-ride quest is done. */
        public boolean lent;
        public long restUntil;
    }

    public static Breed breed(String id) {
        for (Breed b : BREEDS) if (b.id().equals(id)) return b;
        return BREEDS.get(0);
    }

    // ------------------------------------------------------------------ stats

    public static double speedOf(Horse h) {
        return h.speed * (1 + 0.012 * (h.level - 1));
    }

    public static double jumpOf(Horse h) {
        return Math.min(1.2, h.jump + 0.008 * (h.level - 1));
    }

    public static double healthOf(Horse h) {
        return h.health + 0.5 * (h.level - 1);
    }

    public static long xpToNext(Horse h) {
        return 60 + 20L * h.level;
    }

    /** Blocks per second at full gallop (roughly 43 x the speed attribute). */
    public static double bps(double speed) {
        return speed * 43.17;
    }

    private static double roll(net.minecraft.util.math.random.Random r, double lo, double hi) {
        return lo + (hi - lo) * r.nextDouble();
    }

    static Horse create(net.minecraft.util.math.random.Random r, Breed b, String name) {
        Horse h = new Horse();
        h.breed = b.id();
        h.name = name == null || name.isBlank() ? b.title() : name.trim();
        h.cap = b.cap();
        h.speed = roll(r, b.speedMin(), b.speedMax());
        h.jump = roll(r, b.jumpMin(), b.jumpMax());
        h.health = Math.round(roll(r, b.hpMin(), b.hpMax()));
        h.color = r.nextInt(7);
        return h;
    }

    // ------------------------------------------------------------------ owned horses

    private MinecraftServer server;
    /** Player -> the horse entity currently out. */
    private final Map<UUID, UUID> out = new HashMap<>();
    private final Map<UUID, Vec3d> lastPos = new HashMap<>();

    public void open(MinecraftServer server) {
        this.server = server;
        out.clear();
    }

    private static Profile pr(ServerPlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid());
    }

    public static Horse active(Profile pr) {
        for (Horse h : pr.horses) if (h.id.equals(pr.activeHorse)) return h;
        return pr.horses.isEmpty() ? null : pr.horses.get(0);
    }

    private static Horse find(Profile pr, String id) {
        for (Horse h : pr.horses) if (h.id.equals(id)) return h;
        return null;
    }

    /** How many horses this character may keep: the ride-out horse plus home stable space. */
    public int capacity(ServerPlayerEntity p) {
        int stables = 0;
        for (Homes.Deed d : AotRpg.HOMES.deeds(p)) if (d.upgrades.contains(Homes.Upgrade.STABLE.name())) stables++;
        for (var e : AotRpg.HOMES.data.plots.entrySet()) {
            if (e.getValue().stem.equals(AotRpg.HOMES.stem(p)) && e.getValue().stable) stables++;
        }
        return 1 + stables * STABLE_SLOTS;
    }

    private static boolean ours(Entity e) {
        return e.getCommandTags().stream().anyMatch(t -> t.startsWith("aot_horse:"));
    }

    private static String tagOf(Entity e) {
        for (String t : e.getCommandTags()) if (t.startsWith("aot_horse:")) return t.substring(10);
        return "";
    }

    private AbstractHorseEntity entityOut(ServerPlayerEntity p) {
        UUID id = out.get(p.getUuid());
        if (id == null) return null;
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(id);
            if (e instanceof AbstractHorseEntity h && h.isAlive()) return h;
        }
        out.remove(p.getUuid());
        return null;
    }

    /** Stores the horse's current health and removes it from the world. */
    private void putAway(ServerPlayerEntity p) {
        AbstractHorseEntity e = entityOut(p);
        out.remove(p.getUuid());
        if (e == null) return;
        e.removeAllPassengers();
        e.discard();
    }

    private AbstractHorseEntity spawn(ServerPlayerEntity p, Horse h, Vec3d at) {
        ServerWorld w = p.getServerWorld();
        HorseEntity e = EntityType.HORSE.create(w);
        if (e == null) return null;
        e.refreshPositionAndAngles(at.x, at.y, at.z, p.getYaw(), 0);
        e.bondWithPlayer(p);
        e.setVariant(HorseColor.byId(h.color));
        e.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(speedOf(h));
        e.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH).setBaseValue(jumpOf(h));
        e.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(healthOf(h));
        e.setHealth((float) healthOf(h));
        if (h.saddle) e.saddle(new ItemStack(Items.SADDLE), null);
        if (!h.armor.isEmpty()) {
            Identifier id = Identifier.tryParse(h.armor);
            if (id != null && Registries.ITEM.containsId(id)) e.equipStack(EquipmentSlot.BODY, new ItemStack(Registries.ITEM.get(id)));
        }
        e.setCustomName(Text.literal(h.name).formatted(Formatting.GOLD));
        e.setCustomNameVisible(false);
        e.addCommandTag("aot_horse:" + AotRpg.HOMES.stem(p) + ":" + h.id);
        w.spawnEntity(e);
        out.put(p.getUuid(), e.getUuid());
        return e;
    }

    /** The whistle or the call key: calls your ride-out horse, or sends it away if it is right here. */
    public void call(ServerPlayerEntity p) {
        Profile pr = pr(p);
        if (!pr.created) return;
        Horse h = active(pr);
        if (h == null) {
            Notify.toast(p, Text.literal("You have no horse").formatted(Formatting.RED), Text.literal("Visit a Stable Master"), 0xC0463A,
                "minecraft:saddle", null);
            return;
        }
        AbstractHorseEntity e = entityOut(p);
        if (e != null && e.getWorld() == p.getWorld() && e.squaredDistanceTo(p) < 8 * 8 && !p.hasVehicle()) {
            putAway(p);
            Notify.toast(p, Text.literal(h.name + " trots off").formatted(Formatting.GOLD), Text.literal("Whistle again to call"), 0xE0B96A,
                "minecraft:saddle", "horse");
            return;
        }
        if (p.getWorld().getRegistryKey() == Homes.WORLD) {
            Notify.toast(p, Text.literal("Not indoors").formatted(Formatting.RED), null, 0xC0463A, null, "horse");
            return;
        }
        long now = System.currentTimeMillis();
        if (h.restUntil > now) {
            Notify.toast(p, Text.literal(h.name + " is still recovering").formatted(Formatting.RED),
                Text.literal("Ready in " + ((h.restUntil - now) / 60000 + 1) + " min"), 0xC0463A, null, "horse");
            return;
        }
        putAway(p);
        // Arrives from a few blocks behind you.
        Vec3d back = p.getPos().subtract(p.getRotationVec(1f).multiply(1, 0, 1).normalize().multiply(4));
        BlockPos top = p.getServerWorld().getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, BlockPos.ofFloored(back));
        Vec3d at = Math.abs(top.getY() - p.getY()) < 4 ? Vec3d.ofBottomCenter(top) : p.getPos();
        if (spawn(p, h, at) != null) {
            p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_HORSE_GALLOP, SoundCategory.NEUTRAL, 1f, 1f);
            Notify.toast(p, Text.literal(h.name + " answers your whistle").formatted(Formatting.GOLD),
                Text.literal("Lv " + h.level + (h.saddle ? "" : " · no saddle")), 0xE0B96A, "minecraft:saddle", "horse");
        }
    }

    // ------------------------------------------------------------------ tick

    /** Once a second per player: riding XP, the first-ride quest, strays. */
    public void tick(ServerPlayerEntity p, int ticks) {
        if (ticks % 20 != 11) return;
        Profile pr = pr(p);
        AbstractHorseEntity e = entityOut(p);
        if (e == null) return;
        Horse h = find(pr, tagOf(e).substring(tagOf(e).lastIndexOf(':') + 1));
        if (h == null) {
            putAway(p);
            return;
        }
        // Left far behind (another world, or too far): it goes home.
        if (e.getWorld() != p.getWorld() || e.squaredDistanceTo(p) > 160 * 160) {
            putAway(p);
            return;
        }
        if (p.getVehicle() != e) {
            lastPos.remove(p.getUuid());
            return;
        }
        Vec3d now = e.getPos(), was = lastPos.put(p.getUuid(), now);
        if (was == null) return;
        double d = now.distanceTo(was);
        if (d > 40) return; // teleported
        if (h.lent) {
            pr.starterDist += d;
            if (pr.starterDist >= QUEST_DIST) {
                h.lent = false;
                pr.starterQuest = 2;
                Notify.toast(p, Text.literal("The First Ride: complete").formatted(Formatting.GOLD),
                    Text.literal(h.name + " and the saddle are yours now"), 0xE0B96A, "minecraft:saddle", null);
                AotRpg.PROGRESSION.addXp(p, pr, 150);
                AotRpg.PROFILES.save(p.getUuid());
            }
        }
        if (h.level < h.cap) {
            h.xp += Math.round(d);
            if (h.xp >= xpToNext(h)) {
                h.xp -= xpToNext(h);
                h.level++;
                e.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(speedOf(h));
                e.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH).setBaseValue(jumpOf(h));
                e.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(healthOf(h));
                Notify.toast(p, Text.literal(h.name + " reached level " + h.level).formatted(Formatting.GOLD),
                    Text.literal(String.format(java.util.Locale.ROOT, "%.1f blocks/s", bps(speedOf(h)))), 0xE0B96A, "minecraft:golden_carrot", null);
            }
        }
    }

    /** Horses left in the world with nobody to own them (after a restart) are cleared away. */
    public void sweep(ServerWorld w) {
        List<Entity> gone = new ArrayList<>();
        for (Entity e : w.iterateEntities()) {
            if (!ours(e)) continue;
            boolean known = false;
            for (UUID id : out.values()) if (id.equals(e.getUuid())) known = true;
            if (!known) gone.add(e);
        }
        for (Entity e : gone) e.discard();
    }

    public void forget(ServerPlayerEntity p) {
        putAway(p);
        lastPos.remove(p.getUuid());
    }

    /** A horse of ours was about to die: it flees wounded instead (no drops) and rests a while. */
    public boolean spare(AbstractHorseEntity e) {
        if (!ours(e)) return false;
        String tag = tagOf(e);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!e.getUuid().equals(out.get(p.getUuid()))) continue;
            Horse h = find(pr(p), tag.substring(tag.lastIndexOf(':') + 1));
            if (h != null) {
                h.restUntil = System.currentTimeMillis() + 5 * 60_000;
                Notify.toast(p, Text.literal(h.name + " is wounded and flees").formatted(Formatting.RED),
                    Text.literal("It needs 5 minutes to recover"), 0xC0463A, null, "horse");
            }
            out.remove(p.getUuid());
        }
        e.getWorld().playSound(null, e.getBlockPos(), SoundEvents.ENTITY_HORSE_DEATH, SoundCategory.NEUTRAL, 0.7f, 1.2f);
        e.discard();
        return true;
    }

    /** Only the owner rides; the owner's sneak-use opens the horse screen. True when handled. */
    public boolean useHorse(ServerPlayerEntity p, Entity e) {
        if (!(e instanceof AbstractHorseEntity horse)) return false;
        if (!ours(e)) {
            // A tamed horse of yours from before (a spawn egg): adopt it into your stable.
            if (p.isSneaking() && horse.isTame() && p.getUuid().equals(horse.getOwnerUuid())) {
                adopt(p, horse);
                return true;
            }
            return false;
        }
        String tag = tagOf(e);
        if (!tag.startsWith(AotRpg.HOMES.stem(p) + ":")) {
            p.sendMessage(Text.literal("That's someone else's horse.").formatted(Formatting.GRAY), true);
            return true;
        }
        if (p.isSneaking()) {
            send(p, "horse", true);
            return true;
        }
        return false;
    }

    /** The vanilla horse inventory is replaced by the horse screen for our horses. */
    public boolean openInventory(ServerPlayerEntity p, AbstractHorseEntity e) {
        if (!ours(e)) return false;
        if (tagOf(e).startsWith(AotRpg.HOMES.stem(p) + ":")) send(p, "horse", true);
        return true;
    }

    private void adopt(ServerPlayerEntity p, AbstractHorseEntity horse) {
        Profile pr = pr(p);
        if (pr.horses.size() >= capacity(p)) {
            Notify.toast(p, Text.literal("No room in your stables").formatted(Formatting.RED),
                Text.literal("Buy a Stable for your home to keep more horses"), 0xC0463A, null, null);
            return;
        }
        Horse h = new Horse();
        h.breed = "courser";
        h.cap = 25;
        h.name = horse.hasCustomName() ? horse.getCustomName().getString() : "Adopted Horse";
        h.speed = horse.getAttributeBaseValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        h.jump = horse.getAttributeBaseValue(EntityAttributes.GENERIC_JUMP_STRENGTH);
        h.health = horse.getMaxHealth();
        h.saddle = horse.isSaddled();
        if (horse instanceof HorseEntity he) h.color = he.getVariant().getId();
        pr.horses.add(h);
        if (pr.activeHorse.isEmpty()) pr.activeHorse = h.id;
        if (pr.starterQuest == 0) pr.starterQuest = 2;
        horse.discard();
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal(h.name + " joins your stable").formatted(Formatting.GOLD), Text.literal("Call it with N or your whistle"),
            0xE0B96A, "minecraft:saddle", null);
    }

    /** A spawn egg for a named horse (the old Stable Master wares) becomes an owned horse. */
    public boolean useEgg(ServerPlayerEntity p, ItemStack stack) {
        if (!stack.isOf(Items.HORSE_SPAWN_EGG) && !stack.isOf(Items.DONKEY_SPAWN_EGG)) return false;
        NbtComponent data = stack.get(DataComponentTypes.ENTITY_DATA);
        if (data == null) return false;
        NbtCompound nbt = data.copyNbt();
        Profile pr = pr(p);
        if (pr.horses.size() >= capacity(p)) {
            Notify.toast(p, Text.literal("No room in your stables").formatted(Formatting.RED), null, 0xC0463A, null, null);
            return true;
        }
        Horse h = new Horse();
        h.breed = "courser";
        h.cap = 25;
        h.name = stack.getName().getString();
        h.speed = 0.25;
        h.jump = 0.7;
        h.health = 24;
        if (nbt.contains("Attributes")) {
            for (var el : nbt.getList("Attributes", 10)) {
                NbtCompound a = (NbtCompound) el;
                String id = a.getString("id");
                if (id.endsWith("movement_speed")) h.speed = a.getDouble("base");
                if (id.endsWith("jump_strength")) h.jump = a.getDouble("base");
                if (id.endsWith("max_health")) h.health = a.getDouble("base");
            }
        }
        h.color = p.getRandom().nextInt(7);
        pr.horses.add(h);
        if (pr.activeHorse.isEmpty()) pr.activeHorse = h.id;
        if (pr.starterQuest == 0) pr.starterQuest = 2;
        stack.decrement(1);
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal(h.name + " joins your stable").formatted(Formatting.GOLD), Text.literal("Call it with N or your whistle"),
            0xE0B96A, "minecraft:saddle", null);
        return true;
    }

    // ------------------------------------------------------------------ stable masters and homes

    /** Where the player is managing horses: "master" at a Stable Master, "home" at their stables, or "". */
    private final Map<UUID, String> where = new HashMap<>();
    private final Map<UUID, Sector> masterSector = new HashMap<>();

    public static boolean isStableMaster(Entity e) {
        return e.hasCustomName() && e.getCustomName().getString().equals("Stable Master");
    }

    public void openMaster(ServerPlayerEntity p, Entity master) {
        where.put(p.getUuid(), "master");
        masterSector.put(p.getUuid(), Sector.at(master.getX(), master.getZ()));
        send(p, "master", true);
    }

    public void openHome(ServerPlayerEntity p) {
        if (!AotRpg.HOMES.atOwnStable(p)) {
            Notify.toast(p, Text.literal("No stable here").formatted(Formatting.RED),
                Text.literal("Build a Stable at your home or property"), 0xC0463A, "minecraft:hay_block", null);
            return;
        }
        where.put(p.getUuid(), "home");
        send(p, "home", true);
    }

    /** Your horses from the pause menu (a read-only card; managing them needs a stable). */
    public void view(ServerPlayerEntity p) {
        where.remove(p.getUuid());
        send(p, "horse", true);
    }

    private boolean atStable(ServerPlayerEntity p) {
        String w = where.getOrDefault(p.getUuid(), "");
        return !w.isEmpty();
    }

    public void action(ServerPlayerEntity p, String action, String id, String arg) {
        Profile pr = pr(p);
        if (!pr.created) return;
        boolean stable = atStable(p);
        Horse h = find(pr, id);
        switch (action) {
            case "quest" -> {
                if (!stable || pr.starterQuest != 0 || !pr.horses.isEmpty()) break;
                Horse lent = create(p.getRandom(), breed("farm"), arg.isBlank() ? "Clover" : arg);
                lent.saddle = true;
                lent.lent = true;
                pr.horses.add(lent);
                pr.activeHorse = lent.id;
                pr.starterQuest = 1;
                pr.starterDist = 0;
                giveWhistle(p);
                Notify.toast(p, Text.literal("Quest: The First Ride").formatted(Formatting.GOLD),
                    Text.literal("Ride " + lent.name + " " + (int) QUEST_DIST + " blocks. Press N to call it."), 0xE0B96A, "minecraft:saddle", null);
                call(p);
            }
            case "buy" -> buy(p, pr, arg, id);
            case "select" -> {
                if (h == null || !stable || h.lent) break;
                putAway(p);
                pr.activeHorse = h.id;
                Notify.toast(p, Text.literal(h.name + " is your ride-out horse").formatted(Formatting.GOLD), null, 0xE0B96A, "minecraft:saddle", null);
            }
            case "rename" -> {
                if (h == null || !stable) break;
                String n = arg.trim();
                if (n.isEmpty() || n.length() > 24) break;
                h.name = n;
                AbstractHorseEntity e = entityOut(p);
                if (e != null && tagOf(e).endsWith(h.id)) e.setCustomName(Text.literal(n).formatted(Formatting.GOLD));
            }
            case "saddle" -> {
                if (h == null || h.saddle) break;
                if (!AotRpg.WALLET.spendMarks(p, SADDLE)) {
                    Notify.toast(p, Text.literal("A saddle costs " + SADDLE + " Marks").formatted(Formatting.RED), null, 0xC0463A, null, null);
                    break;
                }
                h.saddle = true;
                refreshOut(p, h);
            }
            case "armor" -> {
                if (h == null || !stable) break;
                String item = switch (arg) {
                    case "iron" -> "minecraft:iron_horse_armor";
                    case "gold" -> "minecraft:golden_horse_armor";
                    case "diamond" -> "minecraft:diamond_horse_armor";
                    default -> "";
                };
                long cost = switch (arg) {
                    case "iron" -> ARMOR_IRON;
                    case "gold" -> ARMOR_GOLD;
                    case "diamond" -> ARMOR_DIAMOND;
                    default -> 0;
                };
                if (item.isEmpty()) {
                    h.armor = "";
                } else if (!item.equals(h.armor)) {
                    if (!AotRpg.WALLET.spendMarks(p, cost)) {
                        Notify.toast(p, Text.literal("That armor costs " + cost + " Marks").formatted(Formatting.RED), null, 0xC0463A, null, null);
                        break;
                    }
                    h.armor = item;
                }
                refreshOut(p, h);
            }
            case "dismiss" -> putAway(p);
            case "call" -> call(p);
            case "release" -> {
                if (h == null || !stable || h.lent || pr.horses.size() <= 1) break;
                if (entityOut(p) != null && tagOf(entityOut(p)).endsWith(h.id)) putAway(p);
                pr.horses.remove(h);
                if (h.id.equals(pr.activeHorse)) pr.activeHorse = pr.horses.get(0).id;
                long back = breed(h.breed).price() / 4;
                AotRpg.WALLET.addMarks(p, back, "sold " + h.name);
            }
            default -> { }
        }
        AotRpg.PROFILES.save(p.getUuid());
        send(p, where.getOrDefault(p.getUuid(), "horse"), false);
    }

    private void refreshOut(ServerPlayerEntity p, Horse h) {
        AbstractHorseEntity e = entityOut(p);
        if (e == null || !tagOf(e).endsWith(h.id)) return;
        if (h.saddle && !e.isSaddled()) e.saddle(new ItemStack(Items.SADDLE), SoundCategory.NEUTRAL);
        ItemStack armor = h.armor.isEmpty() ? ItemStack.EMPTY : new ItemStack(Registries.ITEM.get(Identifier.of(h.armor)));
        e.equipStack(EquipmentSlot.BODY, armor);
    }

    private void buy(ServerPlayerEntity p, Profile pr, String breedId, String name) {
        if (!"master".equals(where.get(p.getUuid()))) return;
        Breed b = breed(breedId);
        Sector here = masterSector.getOrDefault(p.getUuid(), Sector.ROSE);
        if (!b.sectors().contains(here)) return;
        if (pr.starterQuest == 1) {
            Notify.toast(p, Text.literal("Finish The First Ride first").formatted(Formatting.RED), null, 0xC0463A, null, null);
            return;
        }
        if (pr.horses.size() >= capacity(p)) {
            Notify.toast(p, Text.literal("No room in your stables").formatted(Formatting.RED),
                Text.literal("Build a Stable at your home or property to keep more horses"), 0xC0463A, null, null);
            return;
        }
        long price = Math.round(b.price() * (1 - Math.min(0.3, 0.02 * pr.total(Stat.CHARISMA))));
        if (!AotRpg.WALLET.spendMarks(p, price)) {
            Notify.toast(p, Text.literal(b.title() + " costs " + price + " Marks").formatted(Formatting.RED), null, 0xC0463A, null, null);
            return;
        }
        Horse h = create(p.getRandom(), b, name);
        pr.horses.add(h);
        if (pr.activeHorse.isEmpty() || active(pr) == null) pr.activeHorse = h.id;
        if (pr.starterQuest == 0) {
            // A first horse bought outright still comes with the Stable Master's saddle.
            pr.starterQuest = 2;
            h.saddle = true;
        }
        giveWhistle(p);
        Notify.toast(p, Text.literal("You bought " + h.name).formatted(Formatting.GOLD),
            Text.literal(b.title() + String.format(java.util.Locale.ROOT, " · %.1f blocks/s", bps(speedOf(h)))), 0xE0B96A, "minecraft:saddle", null);
    }

    private void giveWhistle(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) if (isWhistle(p.getInventory().getStack(i))) return;
        ItemStack w = new ItemStack(Items.GOAT_HORN);
        NbtCompound tag = new NbtCompound();
        tag.putBoolean("aot_whistle", true);
        w.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        w.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Horse Whistle").formatted(Formatting.GOLD).styled(s -> s.withItalic(false)));
        p.getInventory().offerOrDrop(w);
    }

    public static boolean isWhistle(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().getBoolean("aot_whistle");
    }

    public void closed(ServerPlayerEntity p) {
        where.remove(p.getUuid());
    }

    public void send(ServerPlayerEntity p, String mode, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.StableView.ID)) return;
        Profile pr = pr(p);
        List<Net.BreedEntry> breeds = new ArrayList<>();
        if (mode.equals("master")) {
            Sector here = masterSector.getOrDefault(p.getUuid(), Sector.ROSE);
            double disc = Math.min(0.3, 0.02 * pr.total(Stat.CHARISMA));
            for (Breed b : BREEDS) {
                if (!b.sectors().contains(here)) continue;
                breeds.add(new Net.BreedEntry(b.id(), b.title(), b.blurb(), (float) bps(b.speedMin()), (float) bps(b.speedMax()),
                    (float) b.jumpMin(), (float) b.jumpMax(), (int) b.hpMin(), (int) b.hpMax(), b.cap(), Math.round(b.price() * (1 - disc))));
            }
        }
        List<Net.HorseEntry> list = new ArrayList<>();
        AbstractHorseEntity e = entityOut(p);
        for (Horse h : pr.horses) {
            boolean isOut = e != null && tagOf(e).endsWith(h.id);
            list.add(new Net.HorseEntry(h.id, h.name, breed(h.breed).title(), h.level, h.cap, (int) h.xp, (int) xpToNext(h),
                (float) bps(speedOf(h)), (float) jumpOf(h), (float) healthOf(h), h.saddle, h.armor, h.color, h.id.equals(active(pr) == null ? "" : active(pr).id),
                isOut, h.lent, h.restUntil > System.currentTimeMillis()));
        }
        ServerPlayNetworking.send(p, new Net.StableView(mode, breeds, list, capacity(p), pr.starterQuest, (float) pr.starterDist, (float) QUEST_DIST,
            atStable(p), open));
    }
}
