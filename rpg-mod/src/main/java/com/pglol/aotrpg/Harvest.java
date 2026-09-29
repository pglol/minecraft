package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Axes and pickaxes earn their keep. Outside the towns the wild is yours to work: cut a tree at
 * the base with an axe and the whole thing creaks, tips and comes crashing down in the direction
 * you're facing, and its wood (and whatever its leaves held) goes into your satchel. Strike an ore
 * with a pickaxe and the vein follows it, block by block. Underground rock can be dug out anywhere
 * but the towns. What you take grows back in time, when nobody is around to see it.
 */
public final class Harvest {
    private Harvest() {}

    private static final String TAG = "aot_timber";
    /** How long a felled tree or mined vein stays gone (on top of the usual mending delay). */
    private static final long REGROW_TICKS = 20L * 60 * 15;

    // ------------------------------------------------------------------ what may be taken

    private static boolean nearTown(double x, double z) {
        for (Net.Area a : AotRpg.PLACES.areas()) {
            if (!a.look().equals("town")) continue;
            double dx = x - a.x(), dz = z - a.z();
            if (dx * dx + dz * dz < 110 * 110) return true;
        }
        return false;
    }

    public static boolean ore(BlockState s) {
        String p = Registries.BLOCK.getId(s.getBlock()).getPath();
        return p.endsWith("_ore") || p.equals("ancient_debris");
    }

    private static boolean naturalLeaves(BlockState s) {
        return s.getBlock() instanceof LeavesBlock && s.contains(LeavesBlock.PERSISTENT) && !s.get(LeavesBlock.PERSISTENT);
    }

    private static boolean caveRock(BlockState s) {
        return s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.CLAY)
            || s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.COBBLED_DEEPSLATE) || s.isOf(Blocks.CALCITE) || s.isOf(Blocks.DRIPSTONE_BLOCK);
    }

    /** A living tree's log: leaves that grew there (not placed) within reach above it. */
    private static boolean treeLog(World w, BlockPos pos, BlockState s) {
        if (!s.isIn(BlockTags.LOGS)) return false;
        for (BlockPos q : BlockPos.iterate(pos.add(-3, 0, -3), pos.add(3, 12, 3))) if (naturalLeaves(w.getBlockState(q))) return true;
        return false;
    }

    /**
     * May this player take this block, on land that's otherwise protected? The wild outside the
     * towns: trees, their leaves, ores, and rock that's underground.
     */
    public static boolean allowed(net.minecraft.entity.player.PlayerEntity p, BlockPos pos, BlockState s) {
        World w = p.getWorld();
        if (w.getRegistryKey() != World.OVERWORLD || nearTown(pos.getX(), pos.getZ())) return false;
        if (HomePlots.plotAt(pos, 0) >= 0) return false;
        if (ore(s) || naturalLeaves(s) || treeLog(w, pos, s)) return true;
        int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
        return caveRock(s) && pos.getY() < top - 4;
    }

    /** A harvest node (grows back later rather than being forgotten). */
    public static boolean node(BlockState s) {
        return ore(s) || s.isIn(BlockTags.LOGS) || s.getBlock() instanceof LeavesBlock || caveRock(s);
    }

    // ------------------------------------------------------------------ hooks

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp) || !(world instanceof ServerWorld w) || sp.isCreative()) return;
            ItemStack tool = sp.getMainHandStack();
            boolean wild = allowed(sp, pos, state);
            if (wild) {
                if (tool.getItem() instanceof AxeItem && state.isIn(BlockTags.LOGS) && !sp.isSneaking()) fell(w, sp, pos, state);
                else if (tool.getItem() instanceof PickaxeItem && ore(state) && !sp.isSneaking()) vein(w, sp, pos, state);
                skill(sp, state, 1);
            }
            // Whatever it dropped goes into your bag.
            sweep(w, sp, pos);
        });
        // A falling tree interrupted by a restart: its pieces are just cleared away.
        ServerEntityEvents.ENTITY_LOAD.register((e, world) -> {
            if (e.getCommandTags().contains(TAG) && !live.contains(e.getUuid())) e.discard();
        });
    }

    private static void skill(ServerPlayerEntity p, BlockState s, int n) {
        if (s.isIn(BlockTags.LOGS)) Lifestyle.add(p, Crafting.WOODCUTTING, 3L * n);
        else if (ore(s)) Lifestyle.add(p, Crafting.MINING, 6L * n);
    }

    private static void sweep(ServerWorld w, ServerPlayerEntity p, BlockPos pos) {
        for (ItemEntity e : w.getEntitiesByClass(ItemEntity.class, new Box(pos).expand(1.5), x -> x.isAlive() && x.age <= 2 && Loot.owner(x) == null)) {
            Loot.claim(p, e.getStack());
            e.discard();
        }
    }

    /** Drops as if broken with this tool, straight into the bag (merged, so one line per kind). */
    private static void give(ServerPlayerEntity p, Map<net.minecraft.item.Item, Integer> tally) {
        for (var e : tally.entrySet()) {
            int n = e.getValue();
            while (n > 0) {
                ItemStack s = new ItemStack(e.getKey(), Math.min(n, e.getKey().getMaxCount()));
                n -= s.getCount();
                Loot.claim(p, s);
            }
        }
    }

    private static void collect(ServerWorld w, ServerPlayerEntity p, BlockPos pos, BlockState s, ItemStack tool, Map<net.minecraft.item.Item, Integer> tally) {
        for (ItemStack d : Block.getDroppedStacks(s, w, pos, null, p, tool)) tally.merge(d.getItem(), d.getCount(), Integer::sum);
    }

    // ------------------------------------------------------------------ timber

    private record Piece(DisplayEntity.BlockDisplayEntity e, Vector3f off, BlockState state) { }

    private static final class Fall {
        final ServerWorld w;
        final ServerPlayerEntity p;
        final Vec3d pivot;
        final Vector3f axis;
        final List<Piece> pieces = new ArrayList<>();
        final Map<net.minecraft.item.Item, Integer> loot;
        final Direction dir;
        int t;

        Fall(ServerWorld w, ServerPlayerEntity p, Vec3d pivot, Direction dir, Map<net.minecraft.item.Item, Integer> loot) {
            this.w = w;
            this.p = p;
            this.pivot = pivot;
            this.dir = dir;
            this.axis = new Vector3f(dir.getOffsetZ(), 0, -dir.getOffsetX());
            this.loot = loot;
        }
    }

    private static final List<Fall> falls = new ArrayList<>();
    private static final Set<java.util.UUID> live = new HashSet<>();
    private static final int FALL_TICKS = 26;

    private static void fell(ServerWorld w, ServerPlayerEntity p, BlockPos cut, BlockState cutState) {
        // The trunk and branches: logs joined to the cut, level with it or above.
        Set<BlockPos> logs = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(cut);
        Set<BlockPos> seen = new HashSet<>();
        seen.add(cut);
        while (!q.isEmpty() && logs.size() < 180) {
            BlockPos at = q.poll();
            for (BlockPos n : BlockPos.iterate(at.add(-1, 0, -1), at.add(1, 1, 1))) {
                BlockPos m = n.toImmutable();
                if (m.getY() < cut.getY() || !seen.add(m)) continue;
                BlockState s = w.getBlockState(m);
                if (!s.isIn(BlockTags.LOGS) || !AotRpg.CARE.canBuild(p, m) && !allowed(p, m, s)) continue;
                logs.add(m);
                q.add(m);
            }
        }
        if (logs.isEmpty()) return;
        // Its crown: the grown leaves around those logs.
        Set<BlockPos> leaves = new HashSet<>();
        for (BlockPos l : logs) {
            for (BlockPos n : BlockPos.iterate(l.add(-3, -1, -3), l.add(3, 3, 3))) {
                if (leaves.size() >= 420) break;
                if (naturalLeaves(w.getBlockState(n))) leaves.add(n.toImmutable());
            }
        }
        if (leaves.size() < 4) return; // a beam in someone's cabin, not a tree
        ItemStack tool = p.getMainHandStack();
        Map<net.minecraft.item.Item, Integer> loot = new HashMap<>();
        Direction dir = p.getHorizontalFacing();
        Vec3d pivot = new Vec3d(cut.getX() + 0.5, cut.getY(), cut.getZ() + 0.5);
        Fall f = new Fall(w, p, pivot, dir, loot);
        List<BlockPos> all = new ArrayList<>(logs);
        all.addAll(leaves);
        // Show every log, and enough of the crown to read as one (big crowns thinned out).
        int leafEvery = Math.max(1, leaves.size() / 160);
        int li = 0;
        for (BlockPos b : all) {
            BlockState s = w.getBlockState(b);
            collect(w, p, b, s, tool, loot);
            boolean show = s.isIn(BlockTags.LOGS) || (li++ % leafEvery == 0);
            if (show) {
                DisplayEntity.BlockDisplayEntity e = EntityType.BLOCK_DISPLAY.create(w);
                if (e != null) {
                    Vector3f off = new Vector3f((float) (b.getX() - pivot.x), (float) (b.getY() - pivot.y), (float) (b.getZ() - pivot.z));
                    e.addCommandTag(TAG);
                    pose(e, pivot, s, off, new Quaternionf(), 0);
                    if (w.spawnEntity(e)) {
                        live.add(e.getUuid());
                        f.pieces.add(new Piece(e, off, s));
                    }
                }
            }
            w.setBlockState(b, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            AotRpg.CARE.regrowLater(b, REGROW_TICKS);
        }
        AotRpg.CARE.regrowLater(cut, REGROW_TICKS);
        // Wear on the axe: a notch for every few logs.
        tool.damage(Math.max(1, logs.size() / 4), p, EquipmentSlot.MAINHAND);
        skill(p, cutState, logs.size());
        w.playSound(null, cut, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, SoundCategory.BLOCKS, 1f, 0.5f);
        w.playSound(null, cut, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.2f, 0.6f);
        falls.add(f);
    }

    /** Places a piece of the tree as it swings about the stump (rotation q, turned angle so far). */
    private static void pose(DisplayEntity.BlockDisplayEntity e, Vec3d pivot, BlockState s, Vector3f off, Quaternionf q, int interp) {
        Vector3f t = q.transform(new Vector3f(off));
        NbtCompound n = new NbtCompound();
        NbtList pos = new NbtList();
        pos.add(NbtDouble.of(pivot.x));
        pos.add(NbtDouble.of(pivot.y));
        pos.add(NbtDouble.of(pivot.z));
        n.put("Pos", pos);
        NbtList tags = new NbtList();
        tags.add(net.minecraft.nbt.NbtString.of(TAG));
        n.put("Tags", tags);
        n.put("block_state", NbtHelper.fromBlockState(s));
        NbtCompound tr = new NbtCompound();
        tr.put("translation", floats(t.x, t.y, t.z));
        tr.put("left_rotation", floats(q.x, q.y, q.z, q.w));
        tr.put("scale", floats(1, 1, 1));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        n.put("transformation", tr);
        n.putInt("interpolation_duration", interp);
        n.putInt("start_interpolation", 0);
        n.putFloat("view_range", 2.5f);
        e.readNbt(n);
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
    }

    // ------------------------------------------------------------------ veins

    private static final class Vein {
        final ServerWorld w;
        final ServerPlayerEntity p;
        final ArrayDeque<BlockPos> left;
        final Map<net.minecraft.item.Item, Integer> loot = new HashMap<>();

        Vein(ServerWorld w, ServerPlayerEntity p, ArrayDeque<BlockPos> left) {
            this.w = w;
            this.p = p;
            this.left = left;
        }
    }

    private static final List<Vein> veins = new ArrayList<>();

    private static void vein(ServerWorld w, ServerPlayerEntity p, BlockPos start, BlockState state) {
        ArrayDeque<BlockPos> order = new ArrayDeque<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty() && order.size() < 32) {
            BlockPos at = q.poll();
            for (BlockPos n : BlockPos.iterate(at.add(-1, -1, -1), at.add(1, 1, 1))) {
                BlockPos m = n.toImmutable();
                if (!seen.add(m)) continue;
                BlockState s = w.getBlockState(m);
                if (!s.isOf(state.getBlock()) || !allowed(p, m, s)) continue;
                order.add(m);
                q.add(m);
            }
        }
        AotRpg.CARE.regrowLater(start, REGROW_TICKS);
        if (!order.isEmpty()) veins.add(new Vein(w, p, order));
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(MinecraftServer server) {
        if (!veins.isEmpty()) {
            for (var it = veins.iterator(); it.hasNext(); ) {
                Vein v = it.next();
                ItemStack tool = v.p.getMainHandStack();
                boolean done = v.left.isEmpty() || v.p.isRemoved() || !(tool.getItem() instanceof PickaxeItem);
                // The vein gives up a block a tick: a quick crackling run through the rock.
                for (int k = 0; k < 2 && !done; k++) {
                    BlockPos b = v.left.poll();
                    if (b == null) {
                        done = true;
                        break;
                    }
                    BlockState s = v.w.getBlockState(b);
                    if (!ore(s)) continue;
                    collect(v.w, v.p, b, s, tool, v.loot);
                    s.onStacksDropped(v.w, b, tool, true);
                    v.w.breakBlock(b, false, v.p);
                    AotRpg.CARE.regrowLater(b, REGROW_TICKS);
                    skill(v.p, s, 1);
                    tool.damage(1, v.p, EquipmentSlot.MAINHAND);
                    if (tool.isEmpty()) done = true;
                }
                if (done || v.left.isEmpty()) {
                    if (!v.p.isRemoved()) give(v.p, v.loot);
                    it.remove();
                }
            }
        }
        if (falls.isEmpty()) return;
        for (var it = falls.iterator(); it.hasNext(); ) {
            Fall f = it.next();
            f.t++;
            if (f.t <= FALL_TICKS) {
                if (f.t % 2 != 0) continue;
                // Slow to start, then the whole weight of it: angle grows with the square of time.
                float k = f.t / (float) FALL_TICKS;
                float angle = (float) (Math.PI / 2 * 0.96 * k * k);
                Quaternionf q = new Quaternionf().rotateAxis(angle, f.axis.x, f.axis.y, f.axis.z);
                for (Piece pc : f.pieces) if (!pc.e().isRemoved()) pose(pc.e(), f.pivot, pc.state(), pc.off(), q, 2);
                if (f.t == 6) f.w.playSound(null, f.pivot.x, f.pivot.y, f.pivot.z, SoundEvents.BLOCK_BAMBOO_WOOD_BREAK, SoundCategory.BLOCKS, 1.2f, 0.5f);
                if (f.t == FALL_TICKS) impact(f);
                continue;
            }
            if (f.t < FALL_TICKS + 14) continue;
            // Settled: the pieces go, the wood goes in your bag.
            for (Piece pc : f.pieces) {
                live.remove(pc.e().getUuid());
                pc.e().discard();
            }
            if (!f.p.isRemoved()) give(f.p, f.loot);
            it.remove();
        }
    }

    private static void impact(Fall f) {
        ServerWorld w = f.w;
        Vector3f d = new Vector3f(f.dir.getOffsetX(), 0, f.dir.getOffsetZ());
        float len = 0;
        for (Piece pc : f.pieces) len = Math.max(len, pc.off().y);
        // Dust and splinters all along where it came down.
        for (int i = 1; i <= (int) len; i++) {
            double x = f.pivot.x + d.x * i, z = f.pivot.z + d.z * i;
            BlockState wood = f.pieces.isEmpty() ? Blocks.OAK_LOG.getDefaultState() : f.pieces.get(0).state();
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wood), x, f.pivot.y + 0.6, z, 10, 0.5, 0.3, 0.5, 0.1);
            if (i % 3 == 0) w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, f.pivot.y + 0.4, z, 1, 0.3, 0.1, 0.3, 0.01);
        }
        double ix = f.pivot.x + d.x * len * 0.7, iz = f.pivot.z + d.z * len * 0.7;
        w.playSound(null, ix, f.pivot.y, iz, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.BLOCKS, 0.7f, 0.5f);
        w.playSound(null, ix, f.pivot.y, iz, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.5f, 0.5f);
        w.playSound(null, ix, f.pivot.y, iz, SoundEvents.BLOCK_AZALEA_LEAVES_BREAK, SoundCategory.BLOCKS, 1.5f, 0.7f);
        // The ground shakes for whoever's close.
        for (ServerPlayerEntity o : PlayerLookup.around(w, new Vec3d(ix, f.pivot.y, iz), 20)) {
            if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(Math.min(0.5f, 0.12f + len * 0.015f), 8));
        }
    }
}
