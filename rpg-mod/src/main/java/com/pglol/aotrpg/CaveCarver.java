package com.pglol.aotrpg;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Titan caves made to fit titans. The generated great halls were only about fifteen blocks high,
 * so the first time someone comes near a cave its hall is carved out wider and much taller (a dome
 * some 34 blocks across each way and up to 27 high over the floor), a few columns every tick so
 * nothing stalls, and never up through the hillside above. Remembered, so it happens once.
 */
public final class CaveCarver {
    private static final int RADIUS = 34, HEIGHT = 26;

    private final Set<String> done = new HashSet<>();
    private final Set<String> working = new HashSet<>();
    private final ArrayDeque<int[]> columns = new ArrayDeque<>(); // x, z, floorY, topY
    /** Caves being carved, in the order their columns were queued. */
    private final ArrayDeque<String> order = new ArrayDeque<>();
    private Path file;

    public void open(MinecraftServer server) {
        done.clear();
        columns.clear();
        working.clear();
        order.clear();
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("caves_enlarged.txt");
        try {
            if (Files.exists(file)) for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!l.isBlank()) done.add(l.trim());
        } catch (Exception e) {
            AotRpg.LOG.warn("Could not read {}", file, e);
        }
    }

    /** True once this cave's hall is big enough for titans; otherwise queues the carving. */
    public boolean ready(ServerWorld w, Net.Area a) {
        if (done.contains(a.id())) return true;
        if (working.add(a.id())) {
            // Plan the dome: every column inside the circle, from the floor up to its curve.
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    double d2 = (dx * dx + dz * dz) / (double) (RADIUS * RADIUS);
                    if (d2 >= 1) continue;
                    int top = a.y() + 3 + (int) Math.round(HEIGHT * Math.sqrt(1 - d2));
                    columns.add(new int[] {a.x() + dx, a.z() + dz, a.y(), top});
                }
            }
            columns.add(new int[] {Integer.MIN_VALUE, 0, 0, 0}); // end marker for this cave
            order.add(a.id());
        }
        return false;
    }

    /** Every tick: carve some columns. */
    public void tick(ServerWorld w) {
        if (columns.isEmpty()) return;
        WorldCare.quiet(true);
        try {
            for (int n = 0; n < 160 && !columns.isEmpty(); n++) {
                int[] c = columns.peek();
                if (c[0] == Integer.MIN_VALUE) {
                    columns.poll();
                    finish();
                    continue;
                }
                if (!w.isChunkLoaded(c[0] >> 4, c[1] >> 4)) {
                    w.getChunk(c[0] >> 4, c[1] >> 4);
                }
                columns.poll();
                // Never break through: stay well under the ground above.
                int surface = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c[0], c[1]);
                int top = Math.min(c[3], surface - 6);
                BlockPos.Mutable m = new BlockPos.Mutable();
                for (int y = c[2]; y <= top; y++) {
                    m.set(c[0], y, c[1]);
                    BlockState s = w.getBlockState(m);
                    if (s.isAir() || !s.getFluidState().isEmpty() || s.isOf(Blocks.BEDROCK) || w.getBlockEntity(m) != null
                        || s.getHardness(w, m) < 0) continue;
                    w.setBlockState(m, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
                // The odd hanging soul lantern high in the new roof.
                if (top > c[2] + 8 && w.random.nextInt(260) == 0) {
                    m.set(c[0], top, c[1]);
                    if (w.getBlockState(m).isAir() && !w.getBlockState(m.up()).isAir()) {
                        w.setBlockState(m, Blocks.SOUL_LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true), Block.NOTIFY_LISTENERS);
                    }
                }
            }
        } finally {
            WorldCare.quiet(false);
        }
    }

    private void finish() {
        String id = order.poll();
        if (id == null) return;
        done.add(id);
        working.remove(id);
        AotRpg.LOG.info("Enlarged titan cave {}", id);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, done, StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.warn("Could not save {}", file, e);
        }
    }
}
