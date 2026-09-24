package com.pglol.aotworld.core;

import com.pglol.aotworld.core.build.Village;

import java.util.ArrayList;
import java.util.List;

/** Places every village on the map up front so they can be linked by trails. */
public final class Villages {
    public static final int CELL = 1000;
    public final List<Village> all = new ArrayList<>();

    Villages(AotWorld world, RoadNetwork roads, Occupancy occ) {
        Atlas a = world.atlas;
        Column c = new Column();
        for (int cx = Math.floorDiv(a.minX, CELL); cx <= Math.floorDiv(a.maxX, CELL); cx++) {
            for (int cz = Math.floorDiv(a.minZ, CELL); cz <= Math.floorDiv(a.maxZ, CELL); cz++) {
                Village v = create(world, roads, occ, c, cx, cz);
                if (v != null) {
                    all.add(v);
                    occ.add(v.cx, v.cz, v.radius + 70);
                }
            }
        }
    }

    private static Village create(AotWorld world, RoadNetwork roads, Occupancy occ, Column c, int cx, int cz) {
        Atlas atlas = world.atlas;
        long h = Hash.of(world.spec.seed ^ 0x51AA6E, cx, cz);
        if (Hash.unit(h) > 0.6) return null;
        int x = cx * CELL + Hash.range(Hash.mix(h + 1), 150, CELL - 150);
        int z = cz * CELL + Hash.range(Hash.mix(h + 2), 150, CELL - 150);
        double sd = atlas.landSD(x, z);
        if (sd < 70) return null;
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4;
            if (atlas.wallFlatten(x + Math.cos(ang) * 150, z + Math.sin(ang) * 150) > 0) return null;
        }
        if (occ.blocked(x, z, 120)) return null;
        Atlas.Site gf = atlas.site(Atlas.Kind.GIANT_FOREST);
        if (Math.hypot(x - gf.x, z - gf.z) < gf.radius + 100) return null;
        double[] tmp = new double[1];
        for (int dx = -140; dx <= 140; dx += 70) {
            for (int dz = -140; dz <= 140; dz += 70) {
                if (atlas.riverIndex.query(x + dx, z + dz, tmp) < River.Index.INFLUENCE + 10) return null;
            }
        }
        if (roads.nearestPoint(x, z, true, 80) != null) return null;
        world.terrain.sample(x, z, c);
        double m = c.mountain;
        int hc = world.terrain.naturalHeight(x, z);
        if (m > 0.75 || hc > 160 || hc <= WorldSpec.SEA + 1) return null;
        Village.Type type;
        if (atlas.landmass(x, z) == Column.MARLEY) type = Village.Type.MARLEY;
        else if (sd < 160 && Hash.unit(Hash.mix(h + 4)) < 0.8) type = Village.Type.FISHING;
        else if (hc > 95 || m > 0.25) type = Village.Type.HILL;
        else if (c.forest > 0.55) type = Village.Type.FOREST;
        else type = Village.Type.FARM;
        int radius = type == Village.Type.HILL ? 50 : 55;
        return new Village(world, null, type, x, z, radius, Hash.mix(h + 3));
    }
}
