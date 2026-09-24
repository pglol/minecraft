package com.pglol.aotworld.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The geography of the map: coastlines, the three Walls and their districts,
 * rivers, roads, mountain ranges, landmark sites and RPG regions.
 *
 * Canon distances are given in kilometres and multiplied by
 * {@link WorldSpec#blocksPerKm}; buildings and towns keep a fixed block size.
 * Everything here is pure geometry, so it is cheap to query per column.
 *
 * Coordinate convention: +x is east, +z is south. Polar angles are measured
 * from +x towards +z, so 90 degrees is south and 270 degrees is north.
 */
public final class Atlas {
    public static final int EAST = 0, SOUTH = 1, WEST = 2, NORTH = 3;
    public static final int[] DIR_X = {1, 0, -1, 0};
    public static final int[] DIR_Z = {0, 1, 0, -1};

    public final WorldSpec spec;
    private final double k;

    // ---- Walls ---------------------------------------------------------------------------
    public static final class Wall {
        public final String name;
        public final double radius;
        public final List<District> districts = new ArrayList<>();

        Wall(String name, double radius) {
            this.name = name;
            this.radius = radius;
        }
    }

    public static final class District {
        public final String name;
        public final Wall wall;
        public final int dir;
        public final int ux, uz;
        public final double cx, cz, radius;

        District(String name, Wall wall, int dir, double radius) {
            this.name = name;
            this.wall = wall;
            this.dir = dir;
            this.ux = DIR_X[dir];
            this.uz = DIR_Z[dir];
            this.cx = ux * wall.radius;
            this.cz = uz * wall.radius;
            this.radius = radius;
        }

        public boolean inside(double x, double z, double margin) {
            double r = Math.sqrt(x * x + z * z);
            return Mth.dist(x, z, cx, cz) < radius - margin && r > wall.radius + margin;
        }
    }

    public final Wall sina, rose, maria;
    public final List<Wall> walls;
    public final List<District> districts = new ArrayList<>();
    /** Radius of Mitras, the capital at the centre of Wall Sina. */
    public final double capitalRadius = 650;
    public final double districtRadius;

    // ---- Landmark sites ------------------------------------------------------------------
    public enum Kind {
        GIANT_FOREST, UTGARD, REISS_CHAPEL, SURVEY_HQ, TRAINING_CAMP, NAMED_VILLAGE,
        PARADIS_PORT, LIBERIO, MARLEY_PORT, MILITARY_BASE
    }

    public static final class Site {
        public final String name;
        public final Kind kind;
        public final int x, z;
        public final int radius;
        /** Radius of terrain flattening, 0 for none. */
        public final int flatten;
        /** Target height; NaN means "use the natural height at the centre". */
        public double target;
        /** Orientation for coastal sites: direction pointing out to sea. */
        public int seaDir = -1;

        Site(String name, Kind kind, double x, double z, int radius, int flatten, double target) {
            this.name = name;
            this.kind = kind;
            this.x = (int) Math.round(x);
            this.z = (int) Math.round(z);
            this.radius = radius;
            this.flatten = flatten;
            this.target = target;
        }
    }

    public final List<Site> sites = new ArrayList<>();

    // ---- Mountains -----------------------------------------------------------------------
    public static final class Range {
        final double cx, cz, rx, rz, peak;

        Range(double cx, double cz, double rx, double rz, double peak) {
            this.cx = cx;
            this.cz = cz;
            this.rx = rx;
            this.rz = rz;
            this.peak = peak;
        }

        double mask(double x, double z) {
            double dx = (x - cx) / rx, dz = (z - cz) / rz;
            double d = Math.sqrt(dx * dx + dz * dz);
            return 1 - Mth.smoothstep(0.35, 1.0, d);
        }
    }

    public final List<Range> ranges = new ArrayList<>();

    // ---- Rivers & roads ------------------------------------------------------------------
    public final List<River> rivers = new ArrayList<>();
    public final River.Index riverIndex;
    /** Ring roads, one between each pair of walls plus one outside Wall Maria. */
    public final double[] ringRoads;
    /** Straight road segments (x1, z1, x2, z2). */
    public final List<double[]> roads = new ArrayList<>();

    public final List<Region> regions = new ArrayList<>();

    // ---- Coastline ----------------------------------------------------------------------
    /**
     * Paradis outline traced from the reference map, in map pixels. On that map the walls are
     * centred at (455, 605) and Wall Maria has a radius of 90 px, i.e. 480 km.
     */
    private static final int[] OUTLINE = {
        212, 222, 260, 218, 300, 228, 340, 215, 390, 212, 420, 222, 410, 245, 430, 270, 470, 285, 510, 280,
        560, 300, 590, 320, 600, 345, 580, 365, 600, 380, 625, 395, 640, 420, 630, 450, 600, 470, 610, 500,
        640, 520, 660, 560, 655, 600, 640, 630, 650, 660, 690, 690, 720, 720, 740, 760, 745, 800, 760, 840,
        780, 880, 790, 920, 780, 950, 760, 960, 740, 975, 720, 990, 700, 1000, 690, 1030, 660, 1020, 630, 1000,
        600, 980, 570, 960, 540, 945, 500, 930, 470, 915, 440, 900, 420, 880, 410, 850, 390, 820, 370, 790,
        340, 770, 310, 760, 280, 740, 260, 700, 250, 660, 245, 620, 250, 580, 240, 550, 230, 510, 220, 480,
        215, 440, 205, 420, 200, 380, 210, 340, 205, 300, 220, 270, 215, 240
    };
    private static final double MAP_CX = 455, MAP_CZ = 605, KM_PER_PX = 480.0 / 90.0;
    /** Radial compression of the land outside Wall Maria (1 = true to the reference map). */
    public final double islandScale;
    private final double[] coastTable = new double[1440];
    private final Noise coastNoise, coastNoise2;
    private final double[] marleyPhase = new double[4];
    /** Marley: a bulge of coastline at the western edge of the map, centred on marleyZ. */
    private final double marleyZ, marleyHalf, marleyDepth, marleyWest;
    /** Sandy barrens on the south-west coast of Paradis. */
    public final double desertX, desertZ, desertRX, desertRZ;

    public final int minX, maxX, minZ, maxZ;

    public Atlas(WorldSpec spec) {
        this.spec = spec;
        this.k = spec.blocksPerKm;
        SplittableRandom rnd = new SplittableRandom(Hash.of(spec.seed, 0xA71A5));
        coastNoise = new Noise(Hash.of(spec.seed, 11));
        coastNoise2 = new Noise(Hash.of(spec.seed, 12));

        // Walls: canon radii 250 / 380 / 480 km.
        sina = new Wall("Wall Sina", km(250));
        rose = new Wall("Wall Rose", km(380));
        maria = new Wall("Wall Maria", km(480));
        walls = List.of(sina, rose, maria);
        districtRadius = Math.min(320, Math.max(200, 16 * k));

        // District names follow the series where known; the placements are approximate
        // and fan-named districts are marked. Rename or move them freely here.
        addDistrict("Stohess District", sina, EAST);
        addDistrict("Ehrmich District", sina, SOUTH);
        addDistrict("Yalkell District", sina, WEST);
        addDistrict("Orvud District", sina, NORTH);
        addDistrict("Karanes District", rose, EAST);
        addDistrict("Trost District", rose, SOUTH);
        addDistrict("Krolva District", rose, WEST);
        addDistrict("Utopia District", rose, NORTH);
        addDistrict("Maria East District", maria, EAST);   // fan-named
        addDistrict("Shiganshina District", maria, SOUTH);
        addDistrict("Quinta District", maria, WEST);
        addDistrict("Maria North District", maria, NORTH); // fan-named

        // Paradis coastline from the reference map.
        islandScale = spec.islandScale;
        buildCoastTable();
        for (int i = 0; i < 4; i++) marleyPhase[i] = rnd.nextDouble() * Math.PI * 2;

        double[] d = fromMap(530, 900);
        desertX = d[0];
        desertZ = d[1];
        desertRX = km(55);
        desertRZ = km(28);

        // Mountain ranges, positioned on the reference map.
        range(330, 270, 115, 50, 135);   // northern snow peaks
        range(470, 330, 90, 40, 115);    // north-central range
        range(560, 420, 45, 40, 70);     // north-eastern hills
        range(285, 520, 35, 50, 45);     // western hills
        range(650, 790, 55, 70, 60);     // south-eastern hills
        range(330, 690, 45, 30, 40);     // south-western downs
        ranges.add(new Range(km(-150), km(-272), km(55), km(35), 45)); // Reiss hills (inside Rose)

        // Marley sits across the sea to the west of the island's western coast.
        marleyZ = fromMap(250, 700)[1];
        marleyHalf = km(300);
        marleyDepth = km(300);
        double westCoast = 0;
        for (int i = 0; i < coastTable.length; i++) {
            double th = i * 2 * Math.PI / coastTable.length;
            double x = Math.cos(th) * coastTable[i], z = Math.sin(th) * coastTable[i];
            if (Math.abs(z - marleyZ) < marleyHalf) westCoast = Math.min(westCoast, x);
        }
        marleyWest = westCoast - 800 - km(220) - marleyDepth;

        // Rivers.
        int ri = 0;
        for (double a : new double[] {45, 140, 225, 315}) {
            rivers.add(paradisRiver("River " + (++ri), Math.toRadians(a), Math.toRadians(a + 7), rnd));
        }
        riverIndex = new River.Index(rivers);

        ringRoads = new double[] {
            (capitalRadius + sina.radius) / 2,
            (sina.radius + rose.radius) / 2,
            (rose.radius + maria.radius) / 2,
            maria.radius + districtRadius + 600
        };

        placeSites();
        buildRoads();
        buildRegions();

        double px0 = 0, px1 = 0, pz0 = 0, pz1 = 0;
        for (int i = 0; i < coastTable.length; i++) {
            double th = i * 2 * Math.PI / coastTable.length;
            px0 = Math.min(px0, Math.cos(th) * coastTable[i]);
            px1 = Math.max(px1, Math.cos(th) * coastTable[i]);
            pz0 = Math.min(pz0, Math.sin(th) * coastTable[i]);
            pz1 = Math.max(pz1, Math.sin(th) * coastTable[i]);
        }
        int margin = 1500;
        int west = (int) marleyWest, east = (int) px1 + margin;
        int north = (int) pz0 - margin, south = (int) pz1 + margin;
        int side = Math.max(east - west, south - north);
        minX = west;
        maxX = west + side;
        minZ = (north + south) / 2 - side / 2;
        maxZ = minZ + side;
    }

    public double km(double v) {
        return v * k;
    }

    private void addDistrict(String name, Wall wall, int dir) {
        District d = new District(name, wall, dir, districtRadius);
        wall.districts.add(d);
        districts.add(d);
    }

    // ---- Land ----------------------------------------------------------------------------

    /** Converts a pixel on the reference Paradis map to world blocks. */
    public double[] fromMap(double px, double py) {
        double dx = (px - MAP_CX) * KM_PER_PX, dz = (py - MAP_CZ) * KM_PER_PX;
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-9) return new double[] {0, 0};
        double l2 = compress(l);
        return new double[] {dx / l * km(l2), dz / l * km(l2)};
    }

    private double compress(double lKm) {
        return lKm <= 480 ? lKm : 480 + (lKm - 480) * islandScale;
    }

    private void range(double px, double py, double rpx, double rzpx, double peak) {
        double[] c = fromMap(px, py);
        double f = KM_PER_PX * k * islandScale;
        ranges.add(new Range(c[0], c[1], rpx * f, rzpx * f, peak));
    }

    private void buildCoastTable() {
        int n = OUTLINE.length / 2;
        double minR = maria.radius + districtRadius + 900;
        for (int i = 0; i < coastTable.length; i++) {
            double th = i * 2 * Math.PI / coastTable.length;
            double dx = Math.cos(th), dz = Math.sin(th);
            double best = 0;
            for (int e = 0; e < n; e++) {
                double ax = (OUTLINE[2 * e] - MAP_CX) * KM_PER_PX, az = (OUTLINE[2 * e + 1] - MAP_CZ) * KM_PER_PX;
                int f = (e + 1) % n;
                double bx = (OUTLINE[2 * f] - MAP_CX) * KM_PER_PX, bz = (OUTLINE[2 * f + 1] - MAP_CZ) * KM_PER_PX;
                double ex = bx - ax, ez = bz - az;
                double den = dx * ez - dz * ex;
                if (Math.abs(den) < 1e-12) continue;
                double t = (ax * ez - az * ex) / den;   // distance along the ray
                double u = (ax * dz - az * dx) / den;   // position along the edge
                if (t > 0 && u >= 0 && u <= 1) best = Math.max(best, t);
            }
            coastTable[i] = Math.max(minR, km(compress(best)));
        }
    }

    public double paradisCoastRadius(double theta) {
        double f = (theta / (2 * Math.PI)) * coastTable.length;
        f = ((f % coastTable.length) + coastTable.length) % coastTable.length;
        int i = (int) f;
        double t = f - i;
        return Mth.lerp(t, coastTable[i], coastTable[(i + 1) % coastTable.length]);
    }

    private double detail(double x, double z) {
        double big = Math.max(120, 13 * k);
        return coastNoise.fbm(x / 1800, z / 1800, 3) * big + coastNoise2.sample(x / 350, z / 350) * 45;
    }

    /** Signed distance to the Paradis coast in blocks; positive on land. */
    public double paradisSD(double x, double z) {
        double r = Math.sqrt(x * x + z * z);
        if (r < maria.radius + districtRadius + 400) return maria.radius + districtRadius + 900 - r + 400;
        double theta = Math.atan2(z, x);
        return paradisCoastRadius(theta) - r + detail(x, z);
    }

    /** Easting of the Marley coast at a given z (land lies to the west). */
    public double marleyCoastX(double z) {
        double u = (z - marleyZ) / marleyHalf;
        double bump = Math.sqrt(Math.max(0, 1 - u * u));
        double wobble = km(18) * Math.sin(z / km(70) + marleyPhase[0]) + km(22) * coastNoise.fbm(z / km(90) + 50, 3.7, 3);
        return marleyWest + (marleyDepth + wobble) * bump;
    }

    /** Signed distance to the Marley coast in blocks; positive on land. */
    public double marleySD(double x, double z) {
        return marleyCoastX(z) - x + detail(x, z);
    }

    public double landSD(double x, double z) {
        return Math.max(paradisSD(x, z), marleySD(x, z));
    }

    public int landmass(double x, double z) {
        double p = paradisSD(x, z), m = marleySD(x, z);
        if (Math.max(p, m) < 0) return Column.OCEAN;
        return p >= m ? Column.PARADIS : Column.MARLEY;
    }

    /** Walks from (x,z) in direction (dx,dz) until the first ocean block. */
    private double[] coastAlong(double x, double z, double dx, double dz) {
        for (int i = 0; i < 20000; i++) {
            if (landSD(x, z) < 0) return new double[] {x, z};
            x += dx * 8;
            z += dz * 8;
        }
        return new double[] {x, z};
    }

    // ---- Walls queries -------------------------------------------------------------------

    /** 0..1 weight pulling terrain to wall base near walls, districts and Mitras. */
    public double wallFlatten(double x, double z) {
        double r = Math.sqrt(x * x + z * z);
        double d = r - (capitalRadius + 20);
        for (Wall w : walls) d = Math.min(d, Math.abs(r - w.radius) - 40);
        if (d > 400) return 0;
        for (District dist : districts) {
            d = Math.min(d, Mth.dist(x, z, dist.cx, dist.cz) - (dist.radius + 20));
        }
        return 1 - Mth.smoothstep(0, 170, d);
    }

    /** 0 = inside Mitras/Sina, 1 = inside Rose, 2 = inside Maria, 3 = outside the walls. */
    public int ring(double x, double z) {
        double r = Math.sqrt(x * x + z * z);
        if (r < sina.radius) return 0;
        if (r < rose.radius) return 1;
        if (r < maria.radius) return 2;
        return 3;
    }

    public District districtAt(double x, double z) {
        for (District d : districts) if (d.inside(x, z, 0)) return d;
        return null;
    }

    // ---- Rivers --------------------------------------------------------------------------

    private River paradisRiver(String name, double theta0, double theta1, SplittableRandom rnd) {
        double sx = Math.cos(theta0) * km(45), sz = Math.sin(theta0) * km(45);
        double[] coast = coastAlong(Math.cos(theta1) * (maria.radius + 600), Math.sin(theta1) * (maria.radius + 600),
            Math.cos(theta1), Math.sin(theta1));
        double ex = coast[0] + Math.cos(theta1) * 400, ez = coast[1] + Math.sin(theta1) * 400;
        return wiggle(name, sx, sz, ex, ez, 69, rnd);
    }

    private River wiggle(String name, double sx, double sz, double ex, double ez, double srcLevel, SplittableRandom rnd) {
        double len = Mth.dist(sx, sz, ex, ez);
        int n = Math.max(8, (int) (len / 40));
        double nx = -(ez - sz) / len, nz = (ex - sx) / len;
        double amp1 = Math.max(150, 14 * k) * (0.7 + 0.6 * rnd.nextDouble());
        double amp2 = amp1 * 0.35;
        double p1 = rnd.nextDouble() * 6.28, p2 = rnd.nextDouble() * 6.28;
        double f1 = 3 + rnd.nextInt(3), f2 = 11 + rnd.nextInt(6);
        double[] xs = new double[n + 1], zs = new double[n + 1], lv = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double env = Math.sin(Math.PI * t);
            double off = env * (amp1 * Math.sin(t * f1 * Math.PI + p1) + amp2 * Math.sin(t * f2 * Math.PI + p2));
            xs[i] = sx + (ex - sx) * t + nx * off;
            zs[i] = sz + (ez - sz) * t + nz * off;
            lv[i] = WorldSpec.SEA + (srcLevel - WorldSpec.SEA) * Math.pow(1 - t, 0.8);
        }
        return new River(name, xs, zs, lv);
    }

    // ---- Sites ---------------------------------------------------------------------------

    private double[] polar(double deg, double rKm) {
        double a = Math.toRadians(deg);
        return new double[] {Math.cos(a) * km(rKm), Math.sin(a) * km(rKm)};
    }

    private Site site(String name, Kind kind, double[] p, int radius, int flatten, double target) {
        Site s = new Site(name, kind, p[0], p[1], radius, flatten, target);
        sites.add(s);
        return s;
    }

    private void placeSites() {
        site("Forest of Giant Trees", Kind.GIANT_FOREST, polar(120, 430), (int) Math.max(300, km(28)), 0, 0);
        site("Utgard Castle", Kind.UTGARD, polar(300, 335), 45, 45, Double.NaN);
        site("Reiss Chapel", Kind.REISS_CHAPEL, polar(241, 300), 55, 50, Double.NaN);
        site("Survey Corps HQ", Kind.SURVEY_HQ, polar(30, 345), 50, 50, Double.NaN);
        site("Cadet Training Camp", Kind.TRAINING_CAMP, polar(100, 345), 80, 80, Double.NaN);
        site("Ragako Village", Kind.NAMED_VILLAGE, polar(70, 290), 90, 0, 0);

        double[] west = coastAlong(-(maria.radius + districtRadius + 700), marleyZ, -1, 0);
        Site port = site("Paradis Port", Kind.PARADIS_PORT, new double[] {west[0] + 140, west[1]}, 300, 300, 66);
        port.seaDir = WEST;

        double lz = marleyZ - 1500;
        double[] lc = coastAlong(marleyCoastX(lz) - 2500, lz, 1, 0);
        site("Liberio Internment Zone", Kind.LIBERIO, new double[] {lc[0] - 300, lz}, 330, 330, 67).seaDir = EAST;

        double pz = marleyZ + 200;
        double[] pc = coastAlong(marleyCoastX(pz) - 2500, pz, 1, 0);
        site("Marley Port City", Kind.MARLEY_PORT, new double[] {pc[0] - 220, pz}, 420, 420, 66).seaDir = EAST;

        site("Marleyan Military Base", Kind.MILITARY_BASE,
            new double[] {marleyCoastX(marleyZ) - 2300, marleyZ - 700}, 260, 260, Double.NaN);
    }

    /** 0..1, 1 in the middle of the southern sand barrens. */
    public double desert(double x, double z) {
        double dx = (x - desertX) / desertRX, dz = (z - desertZ) / desertRZ;
        return 1 - Mth.smoothstep(0.6, 1.0, Math.sqrt(dx * dx + dz * dz));
    }

    public double marleyCentreZ() {
        return marleyZ;
    }

    public Site site(Kind kind) {
        for (Site s : sites) if (s.kind == kind) return s;
        return null;
    }

    // ---- Roads ---------------------------------------------------------------------------

    private void buildRoads() {
        // Connect inland Paradis landmarks radially to the ring road of their ring.
        for (Site s : sites) {
            if (landmass(s.x, s.z) != Column.PARADIS || s.kind == Kind.GIANT_FOREST) continue;
            double r = Math.sqrt((double) s.x * s.x + (double) s.z * s.z);
            double target = ringRoads[Math.min(ring(s.x, s.z), 3)];
            if (s.kind == Kind.PARADIS_PORT) target = ringRoads[3];
            double f = target / r;
            roads.add(new double[] {s.x, s.z, s.x * f, s.z * f});
        }
        Site liberio = site(Kind.LIBERIO), port = site(Kind.MARLEY_PORT), base = site(Kind.MILITARY_BASE);
        roads.add(new double[] {port.x, port.z, liberio.x, liberio.z});
        roads.add(new double[] {port.x, port.z, base.x, base.z});
        roads.add(new double[] {liberio.x, liberio.z, base.x, base.z});
    }

    /**
     * Distance to the nearest static road centre line, or -1 when further than
     * 2.5 blocks. Village roads are handled by {@link Villages}.
     */
    public double roadDistance(double x, double z, int landmass) {
        double best = 99;
        if (landmass == Column.PARADIS) {
            double r = Math.sqrt(x * x + z * z);
            if (r > capitalRadius - 5) {
                best = Math.min(Math.abs(x), Math.abs(z));
            }
            for (double rr : ringRoads) best = Math.min(best, Math.abs(r - rr));
        }
        for (double[] s : roads) {
            if (x < Math.min(s[0], s[2]) - 3 || x > Math.max(s[0], s[2]) + 3
                || z < Math.min(s[1], s[3]) - 3 || z > Math.max(s[1], s[3]) + 3) continue;
            best = Math.min(best, Mth.segDist(x, z, s[0], s[1], s[2], s[3], null));
        }
        return best <= 2.5 ? best : -1;
    }

    // ---- Regions -------------------------------------------------------------------------

    private static boolean sector(double x, double z, double fromDeg, double toDeg) {
        double a = Math.toDegrees(Math.atan2(z, x));
        if (a < 0) a += 360;
        return fromDeg <= toDeg ? (a >= fromDeg && a < toDeg) : (a >= fromDeg || a < toDeg);
    }

    private void region(String name, String sub, int lo, int hi, int prio, int titans, double wx, double wz,
                        Region.Shape shape) {
        regions.add(new Region(name, sub, lo, hi, prio, titans, (int) wx, (int) wz, shape));
    }

    private void buildRegions() {
        int[][] districtLevels = {
            {28, 32}, {24, 28}, {26, 30}, {32, 36},   // Sina: Stohess, Ehrmich, Yalkell, Orvud
            {16, 20}, {10, 14}, {20, 24}, {24, 28},   // Rose: Karanes, Trost, Krolva, Utopia
            {42, 46}, {1, 5}, {46, 50}, {52, 56}      // Maria: East, Shiganshina, Quinta, North
        };
        for (int i = 0; i < districts.size(); i++) {
            District d = districts.get(i);
            double wx = d.cx + d.ux * (d.radius * 0.5 + 8), wz = d.cz + d.uz * (d.radius * 0.5 + 8);
            region(d.name, d.wall.name, districtLevels[i][0], districtLevels[i][1], 100, 0, wx, wz,
                (x, z) -> d.inside(x, z, 0));
        }
        region("Mitras", "The Royal Capital", 34, 40, 90, 0, 0, 120,
            (x, z) -> x * x + z * z < capitalRadius * capitalRadius);
        region("Underground City", "Beneath Mitras", 30, 36, 110, 0, 270, 20,
            (x, z) -> x * x + z * z < 262 * 262);
        regions.get(regions.size() - 1).yRange(-64, 55);

        for (Site s : sites) {
            int[] lv;
            int titans = 0;
            String sub;
            switch (s.kind) {
                case GIANT_FOREST: lv = new int[] {18, 24}; titans = 21; sub = "Wall Maria"; break;
                case UTGARD: lv = new int[] {22, 26}; titans = 24; sub = "Wall Rose"; break;
                case REISS_CHAPEL: lv = new int[] {38, 42}; sub = "Wall Rose"; break;
                case SURVEY_HQ: lv = new int[] {18, 22}; sub = "Wall Rose"; break;
                case TRAINING_CAMP: lv = new int[] {6, 10}; sub = "104th Cadet Corps"; break;
                case NAMED_VILLAGE: lv = new int[] {14, 18}; titans = 15; sub = "Wall Rose"; break;
                case PARADIS_PORT: lv = new int[] {62, 66}; sub = "Paradis Island"; break;
                case LIBERIO: lv = new int[] {70, 75}; sub = "Marley"; break;
                case MARLEY_PORT: lv = new int[] {72, 78}; sub = "Marley"; break;
                default: lv = new int[] {78, 85}; sub = "Marleyan Army"; break;
            }
            double rr = (double) s.radius * s.radius;
            region(s.name, sub, lv[0], lv[1], 95, titans, s.x, s.z,
                (x, z) -> (x - s.x) * (x - s.x) + (z - s.z) * (z - s.z) < rr);
        }

        double rs = sina.radius, rr = rose.radius, rm = maria.radius;
        region("Inside Wall Sina", "Interior", 28, 36, 40, 0, 0, rs * 0.55,
            (x, z) -> x * x + z * z < rs * rs);
        String[] quad = {"East", "South", "West", "North"};
        double[] from = {315, 45, 135, 225};
        int[][] roseLv = {{16, 22}, {12, 18}, {20, 26}, {24, 30}};
        int[][] mariaLv = {{40, 46}, {5, 12}, {44, 50}, {48, 54}};
        for (int q = 0; q < 4; q++) {
            double f = from[q], t = (f + 90) % 360;
            double wa = Math.toRadians(q * 90);
            double mr = (rs + rr) / 2 + 150, mm = (rr + rm) / 2 + 150;
            region("Wall Rose " + quad[q], "Inside Wall Rose", roseLv[q][0], roseLv[q][1], 31, 0,
                Math.cos(wa) * mr, Math.sin(wa) * mr,
                (x, z) -> { double d = x * x + z * z; return d < rr * rr && sector(x, z, f, t); });
            int tl = (mariaLv[q][0] + mariaLv[q][1]) / 2;
            region("Wall Maria " + quad[q], "Titan Territory", mariaLv[q][0], mariaLv[q][1], 30, tl,
                Math.cos(wa) * mm, Math.sin(wa) * mm,
                (x, z) -> { double d = x * x + z * z; return d < rm * rm && sector(x, z, f, t); });
        }
        double outer = ringRoads[3];
        double dx0 = desertX, dz0 = desertZ, drx = desertRX, drz = desertRZ;
        region("Sand Barrens", "Southern Paradis", 64, 68, 25, 66, dx0, dz0,
            (x, z) -> ((x - dx0) / drx) * ((x - dx0) / drx) + ((z - dz0) / drz) * ((z - dz0) / drz) < 1 && paradisSD(x, z) > 0);
        double[] north = fromMap(360, 300), south = fromMap(620, 900);
        region("Northern Highlands", "Paradis Island", 60, 66, 22, 63, north[0], north[1],
            (x, z) -> paradisSD(x, z) > 0 && z < -maria.radius - 1500);
        region("Southern Reaches", "Paradis Island", 58, 64, 22, 61, south[0], south[1],
            (x, z) -> paradisSD(x, z) > 0 && z > maria.radius + 1500);
        region("Outside the Walls", "Paradis Island", 55, 62, 20, 58, 0, -outer,
            (x, z) -> paradisSD(x, z) > 0);
        Site mbase = site(Kind.MILITARY_BASE);
        region("Marley", "The Marleyan Empire", 72, 82, 20, 0, mbase.x + 400, mbase.z + 600,
            (x, z) -> marleySD(x, z) > 0);
        region("The Sea", "Between Paradis and Marley", 65, 70, 10, 0,
            (marleyCoastX(marleyZ) + site(Kind.PARADIS_PORT).x) / 2, marleyZ,
            (x, z) -> true);

        regions.sort(Comparator.comparingInt((Region r) -> r.priority).reversed());
    }

    public Region regionAt(double x, double y, double z) {
        for (Region r : regions) if (r.contains(x, y, z)) return r;
        return null;
    }

    public List<Region> regions() {
        return Collections.unmodifiableList(regions);
    }

    public Region region(String id) {
        for (Region r : regions) if (r.id().equals(id)) return r;
        return null;
    }
}
