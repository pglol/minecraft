package com.pglol.aotworld.core;

import com.pglol.aotworld.core.build.CapitalFeature;
import com.pglol.aotworld.core.build.Landmarks;
import com.pglol.aotworld.core.build.Style;
import com.pglol.aotworld.core.build.TownFeature;
import com.pglol.aotworld.core.build.TownGrid;
import com.pglol.aotworld.core.build.Village;
import com.pglol.aotworld.core.build.WallFeature;

import java.util.ArrayList;
import java.util.List;

/** Entry point of the generator core: the whole map for one seed and scale. */
public final class AotWorld {
    public final WorldSpec spec;
    public final Atlas atlas;
    public final Terrain terrain;
    public final Villages villages;
    public final ChunkComposer composer;
    private final List<Feature> features;

    public static final double DEFAULT_ISLAND_SCALE = 0.35;

    public AotWorld(long seed, double blocksPerKm) {
        this(seed, blocksPerKm, DEFAULT_ISLAND_SCALE);
    }

    public AotWorld(long seed, double blocksPerKm, double islandScale) {
        spec = new WorldSpec(seed, blocksPerKm, islandScale);
        atlas = new Atlas(spec);
        terrain = new Terrain(atlas);
        villages = new Villages(this);
        terrain.setVillages(villages);
        features = ChunkComposer.sortByLayer(buildFeatures());
        composer = new ChunkComposer(this);
    }

    private List<Feature> buildFeatures() {
        List<Feature> list = new ArrayList<>();
        long seed = spec.seed;
        for (Atlas.Wall w : atlas.walls) list.add(new WallFeature(w, Hash.of(seed, 101)));
        int i = 0;
        for (Atlas.District d : atlas.districts) {
            boolean rich = d.wall == atlas.sina;
            TownGrid grid = new TownGrid((int) d.cx, (int) d.cz, d.dir, WorldSpec.WALL_BASE, Hash.of(seed, 200 + i++),
                (x, z) -> d.inside(x, z, WorldSpec.WALL_HALF + 5),
                rich ? new Style[] {Style.PARADIS[2], Style.PARADIS[4], Style.CAPITAL[0], Style.CAPITAL[1]} : Style.PARADIS,
                rich ? 3 : 2, rich ? 4 : 3, 30,
                new int[] {Blocks.COBBLE, Blocks.COBBLE, Blocks.STONE, Blocks.ANDESITE, Blocks.MOSSY_COBBLE, Blocks.GRAVEL})
                .plaza(d.radius * 0.5, 0, 16);
            int r = (int) d.radius + 2;
            list.add(new TownFeature(grid, (int) d.cx - r, (int) d.cz - r, (int) d.cx + r, (int) d.cz + r));
        }
        list.add(new CapitalFeature(atlas, Hash.of(seed, 300)));
        for (Atlas.Site s : atlas.sites) {
            if (s.kind == Atlas.Kind.NAMED_VILLAGE) {
                list.add(new Village(this, s.name, s.x, s.z, 80, Hash.of(seed, s.x, s.z),
                    false, villages.roadTarget(s.x, s.z, atlas.landmass(s.x, s.z))));
                continue;
            }
            Feature f = Landmarks.create(s, seed);
            if (f != null) list.add(f);
        }
        return list;
    }

    /** Static features plus nearby villages overlapping the box, in render order. */
    public List<Feature> featuresIn(int x0, int z0, int x1, int z1) {
        List<Feature> out = new ArrayList<>();
        for (Village v : villages.near(x0, z0, x1, z1)) out.add(v);
        for (Feature f : features) if (f.intersects(x0, z0, x1, z1)) out.add(f);
        return ChunkComposer.sortByLayer(out);
    }

    public List<Feature> features() {
        return features;
    }

    /** Spawn point: Shiganshina's central plaza. */
    public int[] spawn() {
        Atlas.District d = atlas.districts.get(9);
        int x = (int) (d.cx + d.ux * (d.radius * 0.5 + 8));
        int z = (int) (d.cz + d.uz * (d.radius * 0.5 + 8));
        return new int[] {x, WorldSpec.WALL_BASE + 1, z};
    }
}
