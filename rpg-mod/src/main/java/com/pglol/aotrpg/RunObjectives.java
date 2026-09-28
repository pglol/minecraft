package com.pglol.aotrpg;

import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Objectives out on an island: three per match, each something to do while titans come to stop
 * you, alone or with the squad.
 *
 *   hold    stand at the marker for a minute; titan waves every 15s; step away and it pauses
 *   fires   relight three signal braziers; lighting the first wakes titans
 *   cargo   take it from its chest, then get out with it; you glow and they come for you
 *   escort  someone stranded follows you once you talk to them; get them to a flare
 *   nest    four titans wake when you come near; bring them all down
 *   survey  stand a moment at each of three stakes; one titan comes for each
 *
 * Done: a rich cache appears on the spot, and everyone who helped earns Salvage.
 */
final class RunObjectives {
    private RunObjectives() {}

    static final class Objective {
        int id;
        String type, name;
        BlockPos at;
        final List<BlockPos> spots = new ArrayList<>();
        final Set<Integer> spotsDone = new HashSet<>();
        final Map<Integer, Integer> dwell = new HashMap<>();
        int progress, goal, sinceWave;
        boolean started, done;
        UUID escort, follow;
        final Set<UUID> helpers = new HashSet<>();
    }

    private static final String[][] NAMES = {
        {"hold", "Hold the Signal Relay", "Defend the Supply Uplink", "Hold the Barricade", "Guard the Wagon Repair"},
        {"fires", "Light the Signal Fires", "Rekindle the Watch Braziers", "Raise the Beacon Line"},
        {"cargo", "Recover the Lost Cargo", "Retrieve the Commander's Dispatch", "Salvage the Gas Reserves"},
        {"escort", "Rescue the Stranded Scout", "Escort the Wounded Engineer", "Bring the Deserter Home"},
        {"nest", "Clear the Titan Nest", "Break the Titan Pack", "Cull the Wandering Pack"},
        {"survey", "Survey the Ruins", "Chart the Overlook", "Mark the Old Road"}};
    private static final String CARGO = "aot_cargo";
    private static int nextId;

    /** Three objectives for a new match, spread over the island. */
    static void create(Extraction ex, ServerWorld w, Extraction.Session ses, BlockPos c, Random r) {
        List<String[]> pool = new ArrayList<>(List.of(NAMES));
        for (int i = 0; i < 3 && !pool.isEmpty(); i++) {
            String[] kind = pool.remove(r.nextInt(pool.size()));
            double a = r.nextDouble() * Math.PI * 2, d = 90 + r.nextInt(290);
            BlockPos at = Extraction.land(w, c.getX() + (int) (Math.cos(a) * d), c.getZ() + (int) (Math.sin(a) * d), r, 40);
            if (at == null) continue;
            Objective o = new Objective();
            o.id = nextId++;
            o.type = kind[0];
            o.name = kind[1 + r.nextInt(kind.length - 1)];
            o.at = at;
            switch (o.type) {
                case "hold" -> {
                    o.goal = 120; // half-seconds: a minute
                    place(ex, w, ses, at, Blocks.LODESTONE.getDefaultState());
                }
                case "fires", "survey" -> {
                    o.goal = 3;
                    for (int k = 0; k < 3; k++) {
                        double b = r.nextDouble() * Math.PI * 2, e = 12 + r.nextInt(22);
                        BlockPos sp = Extraction.land(w, at.getX() + (int) (Math.cos(b) * e), at.getZ() + (int) (Math.sin(b) * e), r, 6);
                        if (sp == null) continue;
                        o.spots.add(sp);
                        place(ex, w, ses, sp, o.type.equals("fires")
                            ? Blocks.CAMPFIRE.getDefaultState().with(CampfireBlock.LIT, false)
                            : Blocks.TARGET.getDefaultState());
                    }
                    o.goal = Math.max(1, o.spots.size());
                }
                case "cargo" -> {
                    o.goal = 1;
                    place(ex, w, ses, at, Blocks.CHEST.getDefaultState());
                }
                case "escort" -> {
                    o.goal = 1;
                    VillagerEntity v = EntityType.VILLAGER.create(w);
                    if (v == null) continue;
                    v.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
                    v.setCustomName(Text.literal(o.name.contains("Engineer") ? "Wounded Engineer" : o.name.contains("Deserter") ? "Deserter" : "Stranded Scout")
                        .formatted(Formatting.GOLD));
                    v.setCustomNameVisible(true);
                    v.setInvulnerable(true);
                    v.setPersistent();
                    v.addCommandTag("aot_escort");
                    w.spawnEntity(v);
                    o.escort = v.getUuid();
                }
                case "nest" -> o.goal = 4;
                default -> { }
            }
            ses.objectives.add(o);
        }
    }

    private static void place(Extraction ex, ServerWorld w, Extraction.Session ses, BlockPos at, net.minecraft.block.BlockState st) {
        w.setBlockState(at, st);
        ex.track(ses.island, at);
    }

    /** Twice a second: progress, the titans that come for it, markers for those near. */
    static void tick(Extraction ex, ServerWorld w, Extraction.Session ses, List<ServerPlayerEntity> divers, int ticks) {
        for (Objective o : ses.objectives) {
            if (o.done) continue;
            Vec3d mid = Vec3d.ofBottomCenter(o.at);
            // A marker column so it can be found (only for those within sight of it).
            for (ServerPlayerEntity p : divers) {
                if (p.squaredDistanceTo(mid) > 200 * 200 || ticks % 20 != 0) continue;
                DustParticleEffect gold = new DustParticleEffect(new Vector3f(1f, 0.8f, 0.3f), 1.8f);
                for (int y = 2; y < 40; y += 6) w.spawnParticles(p, gold, true, mid.x, mid.y + y, mid.z, 1, 0.1, 0.4, 0.1, 0);
            }
            switch (o.type) {
                case "hold" -> {
                    boolean anyone = false;
                    for (ServerPlayerEntity p : divers) {
                        if (p.squaredDistanceTo(mid) < 8 * 8) {
                            anyone = true;
                            o.helpers.add(p.getUuid());
                        }
                    }
                    if (anyone) {
                        if (!o.started) announce(divers, o, "Hold here: titans are coming");
                        o.started = true;
                        o.progress++;
                        // The ring you hold, and the waves that come at it.
                        for (int i = 0; i < 16; i++) {
                            double a = i * Math.PI / 8 + ticks * 0.03;
                            w.spawnParticles(ParticleTypes.END_ROD, mid.x + Math.cos(a) * 8, mid.y + 0.3, mid.z + Math.sin(a) * 8, 1, 0, 0, 0, 0);
                        }
                        if (++o.sinceWave >= 30) {
                            o.sinceWave = 0;
                            if (alive(w, o) < 6) Extraction.spawnTitans(w, o.at, 2, 35, tag(o));
                        }
                    }
                }
                case "survey" -> {
                    for (int k = 0; k < o.spots.size(); k++) {
                        if (o.spotsDone.contains(k)) continue;
                        Vec3d sp = Vec3d.ofBottomCenter(o.spots.get(k));
                        boolean here = false;
                        for (ServerPlayerEntity p : divers) {
                            if (p.squaredDistanceTo(sp) < 3.5 * 3.5) {
                                here = true;
                                o.helpers.add(p.getUuid());
                                p.sendMessage(Text.literal("Surveying " + "▮".repeat(o.dwell.getOrDefault(k, 0) + 1)).formatted(Formatting.GOLD), true);
                            }
                        }
                        if (!here) continue;
                        int dw = o.dwell.merge(k, 1, Integer::sum);
                        if (dw == 1) Extraction.spawnTitans(w, o.spots.get(k), 1, 30, tag(o));
                        if (dw >= 10) {
                            o.spotsDone.add(k);
                            o.progress = o.spotsDone.size();
                            w.playSound(null, o.spots.get(k), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundCategory.PLAYERS, 1f, 1f);
                        }
                    }
                }
                case "nest" -> {
                    if (!o.started) {
                        for (ServerPlayerEntity p : divers) {
                            if (p.squaredDistanceTo(mid) < 50 * 50) {
                                o.started = true;
                                o.goal = Math.max(1, Extraction.spawnTitans(w, o.at, 4, 18, tag(o)));
                                announce(divers, o, "The nest wakes");
                                break;
                            }
                        }
                    }
                    for (ServerPlayerEntity p : divers) if (p.squaredDistanceTo(mid) < 60 * 60) o.helpers.add(p.getUuid());
                }
                case "cargo" -> {
                    // The carrier glows: every titan on the island knows where the cargo is.
                    for (ServerPlayerEntity p : divers) {
                        if (carries(p)) {
                            p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false));
                            o.helpers.add(p.getUuid());
                            if (++o.sinceWave >= 50) {
                                o.sinceWave = 0;
                                if (alive(w, o) < 4) Extraction.spawnTitans(w, p.getBlockPos(), 2, 30, tag(o));
                            }
                        }
                    }
                }
                case "escort" -> escort(w, ses, o, divers);
                default -> { }
            }
            if (o.progress >= o.goal && !o.type.equals("cargo") && !o.type.equals("escort")) complete(ex, w, ses, o);
        }
    }

    /** The escort follows whoever spoke to them; titans harry them; reaching a flare saves them. */
    private static void escort(ServerWorld w, Extraction.Session ses, Objective o, List<ServerPlayerEntity> divers) {
        if (!(w.getEntity(o.escort) instanceof VillagerEntity v)) return;
        ServerPlayerEntity lead = null;
        for (ServerPlayerEntity p : divers) if (p.getUuid().equals(o.follow)) lead = p;
        if (lead == null) return;
        o.helpers.add(lead.getUuid());
        double d = v.squaredDistanceTo(lead);
        if (d > 24 * 24) v.refreshPositionAndAngles(lead.getX(), lead.getY(), lead.getZ(), v.getYaw(), 0);
        else if (d > 9) v.getNavigation().startMovingTo(lead, 0.9);
        if (++o.sinceWave >= 50) {
            o.sinceWave = 0;
            if (alive(w, o) < 4) Extraction.spawnTitans(w, v.getBlockPos(), 2, 30, tag(o));
        }
        for (BlockPos e : ses.exits) {
            if (v.squaredDistanceTo(Vec3d.ofBottomCenter(e)) < 7 * 7) {
                w.spawnParticles(ParticleTypes.CLOUD, v.getX(), v.getY() + 1, v.getZ(), 30, 0.4, 1, 0.4, 0.05);
                v.discard();
                o.progress = 1;
                complete(null, w, ses, o);
                return;
            }
        }
    }

    private static String tag(Objective o) {
        return "aot_obj_" + o.id;
    }

    private static int alive(ServerWorld w, Objective o) {
        int n = 0;
        String t = tag(o);
        for (Entity e : w.iterateEntities()) if (e.isAlive() && e.getCommandTags().contains(t)) n++;
        return n;
    }

    private static void announce(List<ServerPlayerEntity> divers, Objective o, String line) {
        for (ServerPlayerEntity p : divers) {
            if (p.squaredDistanceTo(Vec3d.ofCenter(o.at)) > 120 * 120) continue;
            Notify.toast(p, Text.literal(o.name).formatted(Formatting.GOLD, Formatting.BOLD), Text.literal(line), 0xE0B96A, "minecraft:bell", null);
            p.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.HOSTILE, 0.6f, 1f);
        }
    }

    /** Done: a rich cache on the spot, Salvage to everyone who helped. */
    private static void complete(Extraction ex, ServerWorld w, Extraction.Session ses, Objective o) {
        if (o.done) return;
        o.done = true;
        BlockPos at = o.at;
        if (w.getBlockState(at).isOf(Blocks.LODESTONE) || w.getBlockState(at).isOf(Blocks.CHEST)) w.setBlockState(at, Blocks.AIR.getDefaultState());
        BlockPos cache = Extraction.land(w, at.getX() + 1, at.getZ() + 1, w.getRandom(), 3);
        if (cache != null && (w.getBlockState(cache).isAir() || w.getBlockState(cache).isReplaceable())) {
            w.setBlockState(cache, Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, net.minecraft.util.math.Direction.UP));
            if (w.getBlockEntity(cache) instanceof BarrelBlockEntity b) {
                Extraction.fill(b, ses.island.level() + 4, w.getRandom());
                Extraction.fill(b, ses.island.level() + 4, w.getRandom());
            }
            ses.barrels.add(cache);
            if (ex != null) ex.track(ses.island, cache);
            w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, cache.getX() + 0.5, cache.getY() + 1, cache.getZ() + 0.5, 40, 0.4, 0.6, 0.4, 0.3);
        }
        for (UUID id : o.helpers) {
            ServerPlayerEntity p = w.getServer().getPlayerManager().getPlayer(id);
            if (p == null) continue;
            Stash.earn(p, 20);
            Reveal.show(p, "OBJECTIVE COMPLETE", o.name + "  ·  +20 Salvage", "minecraft:bell", 3);
        }
    }

    /** Clicking the objective's blocks: braziers, the cargo chest. True when handled. */
    static boolean use(Extraction ex, Extraction.Session ses, ServerPlayerEntity p, BlockPos pos) {
        ServerWorld w = p.getServerWorld();
        for (Objective o : ses.objectives) {
            if (o.done) continue;
            if (o.type.equals("fires")) {
                int k = o.spots.indexOf(pos);
                if (k < 0 || o.spotsDone.contains(k)) continue;
                o.spotsDone.add(k);
                o.progress = o.spotsDone.size();
                o.helpers.add(p.getUuid());
                w.setBlockState(pos, Blocks.CAMPFIRE.getDefaultState().with(CampfireBlock.LIT, true).with(CampfireBlock.SIGNAL_FIRE, true));
                w.playSound(null, pos, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 1f, 0.8f);
                if (o.progress == 1) {
                    Extraction.spawnTitans(w, o.at, 3, 35, tag(o));
                    announce(List.of(p), o, "The smoke draws them in");
                }
                return true;
            }
            if (o.type.equals("cargo") && pos.equals(o.at) && !o.started) {
                o.started = true;
                o.helpers.add(p.getUuid());
                w.setBlockState(pos, Blocks.AIR.getDefaultState());
                ItemStack cargo = new ItemStack(Items.BUNDLE);
                NbtCompound tag = new NbtCompound();
                tag.putBoolean(CARGO, true);
                tag.putInt("aot_obj", o.id);
                cargo.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
                cargo.set(DataComponentTypes.CUSTOM_NAME, Text.literal(o.name.contains("Dispatch") ? "Commander's Dispatch"
                    : o.name.contains("Gas") ? "Gas Reserves" : "Survey Corps Cargo").formatted(Formatting.GOLD).styled(s -> s.withItalic(false)));
                p.getInventory().offerOrDrop(cargo);
                Extraction.spawnTitans(w, pos, 3, 30, tag(o));
                announce(List.of(p), o, "Get it to a flare: they can see you now");
                return true;
            }
            if (o.type.equals("cargo") && pos.equals(o.at)) return true;
        }
        return false;
    }

    /** Talking to the escort: they follow you. */
    static boolean useEntity(Extraction.Session ses, ServerPlayerEntity p, Entity e) {
        for (Objective o : ses.objectives) {
            if (!o.type.equals("escort") || o.done || !e.getUuid().equals(o.escort)) continue;
            o.follow = p.getUuid();
            o.started = true;
            Notify.toast(p, Text.literal(o.name).formatted(Formatting.GOLD, Formatting.BOLD), Text.literal("They follow you: get them to a flare"),
                0xE0B96A, "minecraft:lead", null);
            return true;
        }
        return false;
    }

    private static boolean carries(ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (isCargo(inv.getStack(i))) return true;
        return false;
    }

    private static boolean isCargo(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().getBoolean(CARGO);
    }

    /** Getting out with the cargo: that's the objective done (and a big payout). */
    static void extracted(Extraction ex, Extraction.Session ses, ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!isCargo(s)) continue;
            inv.setStack(i, ItemStack.EMPTY);
            Stash.earn(p, 60);
            AotRpg.WALLET.addMarks(p, 500, "cargo recovered");
            for (Objective o : ses.objectives) if (o.type.equals("cargo")) o.done = true;
            Reveal.show(p, "CARGO RECOVERED", "+60 Salvage  ·  +500 Marks", "minecraft:bundle", 4);
        }
    }

    /** An objective's titan fell: the nest counts down. */
    static void titanDied(Extraction ex, Extraction.Session ses, Entity dead) {
        for (Objective o : ses.objectives) {
            if (o.done || !o.type.equals("nest") || !dead.getCommandTags().contains(tag(o))) continue;
            o.progress++;
        }
    }

    /** The lines for the HUD: objectives you're near or working on. */
    static List<Net.RunTask> lines(Extraction.Session ses, ServerPlayerEntity p) {
        List<Net.RunTask> out = new ArrayList<>();
        for (Objective o : ses.objectives) {
            boolean near = p.squaredDistanceTo(Vec3d.ofCenter(o.at)) < 90 * 90;
            if (o.done || !(near || o.started || o.helpers.contains(p.getUuid()))) continue;
            if (o.type.equals("hold")) out.add(new Net.RunTask("★ " + o.name + " (s)", o.progress / 2, o.goal / 2));
            else out.add(new Net.RunTask("★ " + o.name, o.progress, o.goal));
        }
        return out;
    }

    /** The match is over: its objective blocks and escorts go. */
    static void clear(ServerWorld w, Extraction.Session ses) {
        for (Objective o : ses.objectives) {
            List<BlockPos> all = new ArrayList<>(o.spots);
            all.add(o.at);
            for (BlockPos bp : all) {
                var st = w.getBlockState(bp);
                if (st.isOf(Blocks.LODESTONE) || st.isOf(Blocks.CAMPFIRE) || st.isOf(Blocks.CHEST) || st.isOf(Blocks.TARGET)) {
                    if (w.getBlockEntity(bp) instanceof net.minecraft.inventory.Inventory inv) inv.clear();
                    w.setBlockState(bp, Blocks.AIR.getDefaultState());
                }
            }
            if (o.escort != null && w.getEntity(o.escort) != null) w.getEntity(o.escort).discard();
        }
    }
}
