package com.pglol.aotworld.core;

import com.pglol.aotworld.core.build.CapitalFeature;
import com.pglol.aotworld.core.build.GiantForest;
import com.pglol.aotworld.core.build.Landmarks;
import com.pglol.aotworld.core.build.Plot;
import com.pglol.aotworld.core.build.Poi;
import com.pglol.aotworld.core.build.Style;
import com.pglol.aotworld.core.build.TownFeature;
import com.pglol.aotworld.core.build.TownGrid;
import com.pglol.aotworld.core.build.Village;
import com.pglol.aotworld.core.build.WallFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Entry point of the generator core: the whole map for one seed and scale.
 *
 * Construction lays the map out in stages: geography, main roads, villages and
 * their trails, the giant forest, lakes, points of interest, property plots and
 * driveways, and finally the levelled pads everything sits on.
 */
public final class AotWorld {
    /** Default horizontal scale: 7 blocks per canon km (about 1:140). */
    public static final double DEFAULT_SCALE = 7;
    /** Default Paradis length, north tip to south tip: about 40 minutes on horseback. */
    public static final double DEFAULT_ISLAND_LENGTH = 19000;

    public final WorldSpec spec;
    public final Atlas atlas;
    public final Terrain terrain;
    public final RoadNetwork roads = new RoadNetwork();
    public final List<Village> villages = new ArrayList<>();
    public final List<Lake> lakes;
    public final List<Poi> pois;
    public final List<Plot> plots = new ArrayList<>();
    public final GiantForest giantForest;
    public final ChunkComposer composer;
    private final List<Feature> big = new ArrayList<>();
    private final SpatialIndex<Feature> small = new SpatialIndex<>(256);
    private final List<Feature> all = new ArrayList<>();

    public AotWorld(long seed) {
        this(seed, DEFAULT_SCALE, Atlas.islandScaleForLength(DEFAULT_SCALE, DEFAULT_ISLAND_LENGTH));
    }

    public AotWorld(long seed, double blocksPerKm, double islandScale) {
        spec = new WorldSpec(seed, blocksPerKm, islandScale);
        atlas = new Atlas(spec);
        terrain = new Terrain(atlas);
        for (Atlas.Site s : atlas.sites) {
            if (s.flatten > 0 && Double.isNaN(s.target)) s.target = Math.max(WorldSpec.SEA + 3, terrain.natural(s.x, s.z));
        }

        mainRoads();
        roads.build();
        terrain.setRoads(roads);

        Occupancy occ = new Occupancy();
        for (Atlas.Site s : atlas.sites) occ.add(s.x, s.z, s.radius + 60);
        Villages placed = new Villages(this, roads, occ);
        villages.addAll(placed.all);
        Atlas.Site ragako = atlas.site(Atlas.Kind.NAMED_VILLAGE);
        if (ragako != null) {
            villages.add(new Village(this, ragako.name, Village.Type.FARM, ragako.x, ragako.z, 70, Hash.of(seed, ragako.x, ragako.z)));
        }
        Scatter.villageTrails(this, roads, villages);
        roads.build();

        giantForest = new GiantForest(this, roads, Hash.of(seed, 0x61A));
        occ.add(giantForest.site.x, giantForest.site.z, giantForest.site.radius + 40);
        lakes = Scatter.lakes(this, roads, occ);
        pois = Scatter.pois(this, roads, occ, lakes);
        List<Plot> scattered = Scatter.plots(this, roads, occ, lakes, villages);

        buildFeatures();
        for (Feature f : new ArrayList<>(all)) {
            if (f instanceof TownFeature) {
                plots.addAll(((TownFeature) f).grid().plotList(f.minX, f.minZ, f.maxX, f.maxZ));
            }
        }
        plots.addAll(scattered);
        plots.addAll(giantForest.plots);
        for (Plot p : scattered) Scatter.driveway(this, roads, p, Hash.of(seed, p.cx(), p.cz(), 4));
        Scatter.poiTrails(this, roads, pois);
        giantForest.addPaths(roads, seed);
        roads.build();

        // Levelled ground.
        SpatialIndex<Pad> pads = new SpatialIndex<>(256);
        for (Atlas.Site s : atlas.sites) {
            if (s.flatten > 0) Pad.circle(s.x, s.z, s.flatten, s.target, 140).index(pads);
        }
        for (Village v : villages) for (Pad p : v.pads()) p.index(pads);
        for (Pad p : giantForest.grove.pads()) p.index(pads);
        for (Poi p : pois) if (p.pad() != null) p.pad().index(pads);
        for (Plot p : scattered) {
            double size = Math.max(p.x1 - p.x0, p.z1 - p.z0);
            Pad.rect(p.x0 - 2, p.z0 - 2, p.x1 + 2, p.z1 + 2, p.y, 10 + size / 3).index(pads);
        }
        terrain.setPads(pads);
        SpatialIndex<Lake> lakeIdx = new SpatialIndex<>(256);
        for (Lake l : lakes) lakeIdx.add(l, l.cx - l.reach(), l.cz - l.reach(), l.cx + l.reach(), l.cz + l.reach());
        terrain.setLakes(lakeIdx);

        for (Village v : villages) addFeature(v);
        addFeature(giantForest.grove);
        addFeature(giantForest.hideout);
        for (Poi p : pois) addFeature(p);
        for (Plot p : plots) addFeature(p);
        all.sort(Comparator.comparingInt(Feature::layer));

        numberPlots();
        missionRegions();
        composer = new ChunkComposer(this);
    }

    // ---- Roads -------------------------------------------------------------------------

    private void mainRoads() {
        for (double r : atlas.ringRoads) roads.add(Road.ring(Road.Type.MAIN, r));
        for (int dir = 0; dir < 4; dir++) {
            double ux = Atlas.DIR_X[dir], uz = Atlas.DIR_Z[dir];
            double end = atlas.capitalRadius;
            while (atlas.paradisSD(ux * end, uz * end) > 0) end += 16;
            roads.add(Road.straight(Road.Type.MAIN, ux * (atlas.capitalRadius - 4), uz * (atlas.capitalRadius - 4), ux * end, uz * end));
        }
        long seed = spec.seed;
        for (Atlas.Site s : atlas.sites) {
            if (atlas.landmass(s.x, s.z) != Column.PARADIS || s.kind == Atlas.Kind.GIANT_FOREST) continue;
            if (s.kind == Atlas.Kind.NAMED_VILLAGE) continue; // linked with the other villages
            double r = Math.hypot(s.x, s.z);
            double target = atlas.ringRoads[Math.min(atlas.ring(s.x, s.z), 3)];
            if (s.kind == Atlas.Kind.PARADIS_PORT) target = atlas.ringRoads[3];
            double[] e = atlas.exit(s, s.x * target / r, s.z * target / r);
            double er = Math.hypot(e[2], e[3]);
            double f = target / er;
            Road.Type type = s.kind == Atlas.Kind.PARADIS_PORT ? Road.Type.MAIN : Road.Type.TRAIL;
            roads.add(Road.straight(type, e[0], e[1], e[2], e[3]));
            Road road = Road.curve(type, e[2], e[3], e[2] * f, e[3] * f, Hash.of(seed, s.x, s.z, 9));
            roads.add(Scatter.routeOk(this, road, s) ? road : Road.straight(type, e[2], e[3], e[2] * f, e[3] * f));
        }
        Atlas.Site liberio = atlas.site(Atlas.Kind.LIBERIO), port = atlas.site(Atlas.Kind.MARLEY_PORT);
        Atlas.Site base = atlas.site(Atlas.Kind.MILITARY_BASE);
        link(port, liberio, 31);
        link(port, base, 32);
        link(liberio, base, 33);
    }

    /** A paved road between two Marley sites, leaving each through its gate. */
    private void link(Atlas.Site a, Atlas.Site b, int salt) {
        double[] ea = atlas.exit(a, b.x, b.z), eb = atlas.exit(b, a.x, a.z);
        roads.add(Road.straight(Road.Type.PAVED, ea[0], ea[1], ea[2], ea[3]));
        roads.add(Road.straight(Road.Type.PAVED, eb[0], eb[1], eb[2], eb[3]));
        roads.add(Road.curve(Road.Type.PAVED, ea[2], ea[3], eb[2], eb[3], Hash.of(spec.seed, salt)));
    }

    // ---- Features ----------------------------------------------------------------------

    private void addFeature(Feature f) {
        all.add(f);
        int w = f.maxX - f.minX, h = f.maxZ - f.minZ;
        if (w > 2000 || h > 2000) big.add(f);
        else small.add(f, f.minX, f.minZ, f.maxX, f.maxZ);
    }

    private void buildFeatures() {
        long seed = spec.seed;
        for (Atlas.Wall w : atlas.walls) addFeature(new WallFeature(w, Hash.of(seed, 101)));
        int i = 0;
        for (Atlas.District d : atlas.districts) {
            boolean rich = d.wall == atlas.sina;
            TownGrid grid = new TownGrid((int) d.cx, (int) d.cz, d.dir, WorldSpec.WALL_BASE, Hash.of(seed, 200 + i++),
                (x, z) -> d.inside(x, z, WorldSpec.WALL_HALF + 5),
                rich ? new Style[] {Style.PARADIS[2], Style.PARADIS[4], Style.CAPITAL[0], Style.CAPITAL[1]} : Style.PARADIS,
                rich ? 3 : 2, rich ? 4 : 3, 30,
                new int[] {Blocks.COBBLE, Blocks.COBBLE, Blocks.STONE, Blocks.ANDESITE, Blocks.MOSSY_COBBLE, Blocks.GRAVEL})
                .plaza(d.radius * 0.5, 0, 16).plots(0.06);
            int r = (int) d.radius + 2;
            addFeature(new TownFeature(grid, (int) d.cx - r, (int) d.cz - r, (int) d.cx + r, (int) d.cz + r));
        }
        addFeature(new CapitalFeature(atlas, Hash.of(seed, 300)));
        for (Atlas.Site s : atlas.sites) {
            if (s.kind == Atlas.Kind.NAMED_VILLAGE) continue;
            Feature f = Landmarks.create(s, seed, atlas);
            if (f != null) addFeature(f);
        }
    }

    private void numberPlots() {
        plots.sort(Comparator.comparingInt((Plot p) -> p.cz()).thenComparingInt(Plot::cx));
        int id = 1;
        for (Plot p : plots) {
            p.id = id++;
            Region r = atlas.regionAt(p.cx(), p.y + 1, p.cz());
            p.region = r == null ? "" : r.name;
        }
    }

    /** Titan caves, expedition camps and the forest hideouts get their own zones. */
    private void missionRegions() {
        for (Poi p : pois) {
            if (p.kind != Poi.Kind.TITAN_CAVE && p.kind != Poi.Kind.EXPEDITION_CAMP) continue;
            Region around = atlas.regionAt(p.x, p.y, p.z);
            int lo = around == null ? 50 : around.minLevel + 2, hi = around == null ? 55 : around.maxLevel + 4;
            boolean cave = p.kind == Poi.Kind.TITAN_CAVE;
            int[] e = p.entrance();
            double rr = cave ? 70 : 40;
            int px = p.x, pz = p.z;
            Region reg = new Region(p.name, cave ? "Titan Cave" : "Survey Corps Camp", lo, hi, 97, cave ? hi : 0,
                cave ? e[0] : p.x, cave ? e[1] : p.z, (x, z) -> (x - px) * (x - px) + (z - pz) * (z - pz) < rr * rr);
            atlas.addRegion(reg);
        }
        GiantForest g = giantForest;
        int gx = g.groveX, gz = g.groveZ;
        atlas.addRegion(new Region("Hidden Grove", "Forest of Giant Trees", 20, 24, 98, 0, gx, gz,
            (x, z) -> (x - gx) * (x - gx) + (z - gz) * (z - gz) < 50 * 50));
        GiantForest.Tree t0 = g.hideout.trees.get(0);
        int hx = t0.x, hz = t0.z;
        atlas.addRegion(new Region("Canopy Hideout", "Forest of Giant Trees", 22, 26, 98, 0, hx + 6, hz + 6,
            (x, z) -> (x - hx) * (x - hx) + (z - hz) * (z - hz) < 70 * 70));
    }

    /** Big features plus the small ones overlapping the box, in render order. */
    public List<Feature> featuresIn(int x0, int z0, int x1, int z1) {
        List<Feature> out = new ArrayList<>();
        for (Feature f : big) if (f.intersects(x0, z0, x1, z1)) out.add(f);
        java.util.Set<Feature> seen = new java.util.HashSet<>();
        for (int x = x0; x <= x1 + 255; x += 256) {
            for (int z = z0; z <= z1 + 255; z += 256) {
                for (Feature f : small.at(Math.min(x, x1), Math.min(z, z1))) {
                    if (seen.add(f) && f.intersects(x0, z0, x1, z1)) out.add(f);
                }
            }
        }
        out.sort(Comparator.comparingInt(Feature::layer));
        return out;
    }

    public List<Feature> features() {
        return all;
    }

    /** Inside the levelled area of a coastal town (ports, Liberio). */
    public boolean inHarbour(int x, int z) {
        for (Atlas.Site s : atlas.sites) {
            if (s.seaDir < 0) continue;
            if (Math.hypot(x - s.x, z - s.z) < s.flatten + 30) return true;
        }
        return false;
    }

    /** Spawn point: Shiganshina's central plaza. */
    public int[] spawn() {
        Atlas.District d = atlas.districts.get(9);
        int x = (int) (d.cx + d.ux * (d.radius * 0.5 + 8));
        int z = (int) (d.cz + d.uz * (d.radius * 0.5 + 8));
        return new int[] {x, WorldSpec.WALL_BASE + 1, z};
    }
}
