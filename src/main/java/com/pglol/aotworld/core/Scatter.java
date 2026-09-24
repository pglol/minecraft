package com.pglol.aotworld.core;

import com.pglol.aotworld.core.build.Plot;
import com.pglol.aotworld.core.build.Poi;
import com.pglol.aotworld.core.build.Style;
import com.pglol.aotworld.core.build.Village;

import java.util.ArrayList;
import java.util.List;

/** Places lakes, points of interest and property plots, and routes their trails. */
final class Scatter {
    private Scatter() {}

    private static final String[] CAVE_NAMES = {
        "Hollow of Bones", "The Gaping Maw", "Stillwater Grotto", "Ravenfall Cavern", "The Sleeping Pit",
        "Ashen Burrow", "Howling Rift", "Deeproot Den", "The Titan's Throat", "Greymoss Cave",
        "Shattered Vault", "Blackstone Hollow", "Duskhollow", "The Silent Nest", "Wolfjaw Cavern",
        "Emberdeep", "Crooked Tunnels", "The Ossuary", "Mistveil Cave", "Stormbreak Hollow"
    };
    private static final String[] CAMP_NAMES = {
        "Forward Camp", "Supply Post", "Scout Camp", "Relay Station", "Waystation", "Outpost"
    };
    private static final String[] CAMP_PLACES = {
        "Erwin", "Hange", "Mike", "Levi", "Nanaba", "Moblit", "Keith", "Shadis", "Petra", "Gunther",
        "Oluo", "Eld", "Nifa", "Ness", "Dita", "Marlowe"
    };

    static List<Lake> lakes(AotWorld w, RoadNetwork roads, Occupancy occ) {
        Atlas a = w.atlas;
        List<Lake> out = new ArrayList<>();
        int cell = 1100;
        double[] tmp = new double[1];
        for (int cx = Math.floorDiv(a.minX, cell); cx <= Math.floorDiv(a.maxX, cell); cx++) {
            for (int cz = Math.floorDiv(a.minZ, cell); cz <= Math.floorDiv(a.maxZ, cell); cz++) {
                long h = Hash.of(w.spec.seed ^ 0x1A4E, cx, cz);
                if (Hash.unit(h) > 0.5) continue;
                int x = cx * cell + Hash.range(Hash.mix(h + 1), 150, cell - 150);
                int z = cz * cell + Hash.range(Hash.mix(h + 2), 150, cell - 150);
                double r = 30 + Hash.unit(Hash.mix(h + 3)) * 60;
                if (a.landSD(x, z) < r + 250) continue;
                if (w.terrain.mountainMask(x, z) > 0.5) continue;
                boolean ok = !occ.blocked(x, z, r + 40);
                for (int i = 0; i < 12 && ok; i++) {
                    double ang = i * Math.PI / 6;
                    double px = x + Math.cos(ang) * (r * 1.3 + 30), pz = z + Math.sin(ang) * (r * 1.3 + 30);
                    if (a.wallFlatten(px, pz) > 0 || roads.clearance(px, pz) < 6
                        || a.riverIndex.query(px, pz, tmp) < River.Index.INFLUENCE + 10) ok = false;
                }
                for (double gx = -r * 1.4; gx <= r * 1.4 && ok; gx += 10) {
                    for (double gz = -r * 1.4; gz <= r * 1.4 && ok; gz += 10) {
                        if (gx * gx + gz * gz <= r * r * 1.96 && roads.clearance(x + gx, z + gz) < 4) ok = false;
                    }
                }
                if (!ok) continue;
                int level = Integer.MAX_VALUE;
                for (int i = 0; i < 16; i++) {
                    double ang = i * Math.PI / 8;
                    level = Math.min(level, w.terrain.naturalHeight(x + Math.cos(ang) * r * 1.2, z + Math.sin(ang) * r * 1.2) - 1);
                }
                if (level < WorldSpec.SEA + 2) continue;
                out.add(new Lake(x, z, r, level, 3 + Hash.unit(Hash.mix(h + 4)) * 4, Hash.mix(h + 5)));
                occ.add(x, z, r);
            }
        }
        return out;
    }

    static List<Poi> pois(AotWorld w, RoadNetwork roads, Occupancy occ, List<Lake> lakes) {
        Atlas a = w.atlas;
        List<Poi> out = new ArrayList<>();
        Column c = new Column();
        int caves = 0, camps = 0;
        // Expedition camps around the Forest of Giant Trees.
        Atlas.Site gf = a.site(Atlas.Kind.GIANT_FOREST);
        for (int i = 0; i < 6 && camps < 3; i++) {
            double ang = i * Math.PI / 3 + 0.4;
            int x = (int) (gf.x + Math.cos(ang) * (gf.radius + 70)), z = (int) (gf.z + Math.sin(ang) * (gf.radius + 70));
            if (!poiOk(w, roads, occ, lakes, x, z, 45, false)) continue;
            out.add(new Poi(w, Poi.Kind.EXPEDITION_CAMP, campName(camps), x, z, Hash.of(w.spec.seed, 700 + i)));
            occ.add(x, z, 60);
            camps++;
        }
        // Shipwrecks washed up along the Paradis coast.
        for (int i = 0; i < 28; i++) {
            long h = Hash.of(w.spec.seed ^ 0x5419, i);
            if (Hash.unit(h) > 0.55) continue;
            double ang = i * Math.PI * 2 / 28 + Hash.unit(Hash.mix(h + 1)) * 0.15;
            double dx = Math.cos(ang), dz = Math.sin(ang), r = a.maria.radius + 500;
            while (a.landSD(dx * r, dz * r) > 10 && r < 60000) r += 8;
            int x = (int) (dx * r), z = (int) (dz * r);
            if (!poiOk(w, roads, occ, lakes, x, z, 22, true)) continue;
            out.add(new Poi(w, Poi.Kind.SHIPWRECK, "Shipwreck", x, z, Hash.mix(h + 2)));
            occ.add(x, z, 30);
        }
        int cell = 600;
        for (int cx = Math.floorDiv(a.minX, cell); cx <= Math.floorDiv(a.maxX, cell); cx++) {
            for (int cz = Math.floorDiv(a.minZ, cell); cz <= Math.floorDiv(a.maxZ, cell); cz++) {
                long h = Hash.of(w.spec.seed ^ 0xB015, cx, cz);
                double roll = Hash.unit(h);
                if (roll > 0.6) continue;
                int x = cx * cell + Hash.range(Hash.mix(h + 1), 100, cell - 100);
                int z = cz * cell + Hash.range(Hash.mix(h + 2), 100, cell - 100);
                double sd = a.landSD(x, z);
                if (sd < 3) continue;
                int land = a.landmass(x, z);
                int ring = land == Column.PARADIS ? a.ring(x, z) : 4;
                w.terrain.sample(x, z, c);
                int hc = w.terrain.naturalHeight(x, z);
                int[] top = summit(w, x, z, 200);
                boolean hilltop = top[2] > 80 && isHilltop(w, top[0], top[1], top[2], 80, 6);
                if (hilltop && pick(h) < 0.5) {
                    x = top[0];
                    z = top[1];
                    hc = top[2];
                    sd = a.landSD(x, z);
                }
                double slope = 0;
                for (int i = 0; i < 8; i++) {
                    double ang = i * Math.PI / 4;
                    slope = Math.max(slope, hc - w.terrain.naturalHeight(x + Math.cos(ang) * 55, z + Math.sin(ang) * 55));
                }
                Poi.Kind kind;
                double pick = Hash.unit(Hash.mix(h + 3));
                if (sd < 60) continue;
                else if (hilltop && x == top[0] && z == top[1]) kind = Poi.Kind.WATCHTOWER;
                else if (ring >= 2 && ring <= 3 && (ring == 2 ? pick < 0.35 : (slope > 7 || c.mountain > 0.2) && hc > 76 && pick < 0.85)) kind = Poi.Kind.TITAN_CAVE;
                else if (ring >= 2 && ring <= 3 && pick < 0.12) kind = Poi.Kind.EXPEDITION_CAMP;
                else if (hilltop && x == top[0] && z == top[1]) kind = Poi.Kind.WATCHTOWER;
                else if (c.mountain > 0.3) kind = Poi.Kind.HERMIT;
                else if (c.forest > 0.5) kind = Poi.Kind.CAMPSITE;
                else if (pick < 0.45) kind = Poi.Kind.SHRINE;
                else continue;
                int r = kind == Poi.Kind.TITAN_CAVE ? 62 : kind.reach + 10;
                if (kind == Poi.Kind.TITAN_CAVE ? !caveOk(w, roads, occ, lakes, x, z)
                                                : !poiOk(w, roads, occ, lakes, x, z, r, kind == Poi.Kind.SHIPWRECK)) continue;
                String name;
                switch (kind) {
                    case TITAN_CAVE: name = CAVE_NAMES[caves % CAVE_NAMES.length] + (caves >= CAVE_NAMES.length ? " II" : ""); caves++; break;
                    case EXPEDITION_CAMP: name = campName(camps++); break;
                    case WATCHTOWER: name = "Ruined Watchtower"; break;
                    case HERMIT: name = "Hermit's Cabin"; break;
                    case CAMPSITE: name = "Travellers' Camp"; break;
                    case SHRINE: name = "Old Stone Circle"; break;
                    default: name = "Shipwreck"; break;
                }
                Poi p = new Poi(w, kind, name, x, z, Hash.mix(h + 4));
                out.add(p);
                occ.add(x, z, r);
            }
        }
        return out;
    }

    private static double pick(long h) {
        return Hash.unit(Hash.mix(h + 3));
    }

    private static String campName(int i) {
        return CAMP_PLACES[i % CAMP_PLACES.length] + "'s " + CAMP_NAMES[(i / 2) % CAMP_NAMES.length];
    }

    /** Caves are mostly underground: only the surface around the middle and the mouth must be clear. */
    private static boolean caveOk(AotWorld w, RoadNetwork roads, Occupancy occ, List<Lake> lakes, int x, int z) {
        Atlas a = w.atlas;
        if (occ.blocked(x, z, 45) || a.landSD(x, z) < 110) return false;
        if (a.wallFlatten(x, z) > 0 || roads.clearance(x, z) < 20) return false;
        double[] tmp = new double[1];
        for (int dx = -60; dx <= 60; dx += 30) {
            for (int dz = -60; dz <= 60; dz += 30) {
                if (a.riverIndex.query(x + dx, z + dz, tmp) < River.Index.INFLUENCE + 20) return false;
                for (Lake l : lakes) if (l.norm(x + dx, z + dz) < 1.4) return false;
            }
        }
        return true;
    }

    private static boolean poiOk(AotWorld w, RoadNetwork roads, Occupancy occ, List<Lake> lakes, int x, int z, int r, boolean coast) {
        Atlas a = w.atlas;
        if (occ.blocked(x, z, r)) return false;
        if (!coast && a.landSD(x, z) < r) return false;
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4;
            double px = x + Math.cos(ang) * r * 0.8, pz = z + Math.sin(ang) * r * 0.8;
            if (a.wallFlatten(px, pz) > 0) return false;
        }
        if (roads.clearance(x, z) < Math.min(r, 40)) return false;
        double[] tmp = new double[1];
        if (a.riverIndex.query(x, z, tmp) < River.Index.INFLUENCE + r * 0.5) return false;
        for (Lake l : lakes) if (Mth.dist(x, z, l.cx, l.cz) < l.r * 1.3 + r) return false;
        return true;
    }

    /** Highest of a 5x5 sample grid around (x, z), as {x, z, height}. */
    static int[] summit(AotWorld w, int x, int z, int span) {
        int[] best = {x, z, Integer.MIN_VALUE};
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                int px = x + i * span / 4, pz = z + j * span / 4;
                int h = w.terrain.naturalHeight(px, pz);
                if (h > best[2]) best = new int[] {px, pz, h};
            }
        }
        return best;
    }

    static boolean isHilltop(AotWorld w, int x, int z, int hc, int dist, int rise) {
        int higher = 0;
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4;
            if (w.terrain.naturalHeight(x + Math.cos(ang) * dist, z + Math.sin(ang) * dist) > hc - rise) higher++;
        }
        return higher <= 2;
    }

    // ---- Plots ---------------------------------------------------------------------------

    static List<Plot> plots(AotWorld w, RoadNetwork roads, Occupancy occ, List<Lake> lakes, List<Village> villages) {
        Atlas a = w.atlas;
        List<Plot> out = new ArrayList<>();
        Column c = new Column();
        // Plots on the edge of each village.
        for (Village v : villages) {
            if (v.type == Village.Type.HIDDEN) continue;
            int n = Hash.range(Hash.of(w.spec.seed, v.cx, v.cz), 2, 4);
            for (int i = 0; i < 10 && n > 0; i++) {
                long h = Hash.of(w.spec.seed ^ 0x7107, v.cx + i, v.cz);
                double ang = Hash.unit(h) * Math.PI * 2;
                int size = Hash.unit(Hash.mix(h + 1)) < 0.55 ? 14 : 20;
                int x = v.cx + (int) (Math.cos(ang) * (v.radius + 22 + size / 2));
                int z = v.cz + (int) (Math.sin(ang) * (v.radius + 22 + size / 2));
                Plot p = tryPlot(w, roads, occ, lakes, Plot.Kind.VILLAGE, x, z, size, false);
                if (p != null) {
                    out.add(p);
                    n--;
                }
            }
        }
        // One or two plots on the shore of each lake.
        for (Lake l : lakes) {
            int n = 0;
            for (int i = 0; i < 8 && n < 2; i++) {
                long h = Hash.of(w.spec.seed ^ 0x1A7E, (long) l.cx, (long) l.cz + i);
                double ang = Hash.unit(h) * Math.PI * 2;
                int size = Hash.unit(Hash.mix(h + 1)) < 0.5 ? 20 : 28;
                double rr = l.r * 1.3 + 8 + size / 2.0;
                Plot p = tryPlot(w, roads, occ, lakes, Plot.Kind.LAKESIDE, (int) (l.cx + Math.cos(ang) * rr),
                    (int) (l.cz + Math.sin(ang) * rr), size, false);
                if (p != null) {
                    out.add(p);
                    n++;
                }
            }
        }
        // Scattered countryside plots.
        int cell = 380;
        for (int cx = Math.floorDiv(a.minX, cell); cx <= Math.floorDiv(a.maxX, cell); cx++) {
            for (int cz = Math.floorDiv(a.minZ, cell); cz <= Math.floorDiv(a.maxZ, cell); cz++) {
                long h = Hash.of(w.spec.seed ^ 0x9107, cx, cz);
                if (Hash.unit(h) > 0.5) continue;
                int x = cx * cell + Hash.range(Hash.mix(h + 1), 40, cell - 40);
                int z = cz * cell + Hash.range(Hash.mix(h + 2), 40, cell - 40);
                double sd = a.landSD(x, z);
                if (sd < 45) continue;
                w.terrain.sample(x, z, c);
                int hc = w.terrain.naturalHeight(x, z);
                double pick = Hash.unit(Hash.mix(h + 3));
                Plot.Kind kind;
                int size;
                Lake shore = null;
                for (Lake l : lakes) if (Mth.dist(x, z, l.cx, l.cz) < l.r + 140) shore = l;
                int[] top = summit(w, x, z, 300);
                boolean summitOk = pick < 0.6 && top[2] > 80 && isHilltop(w, top[0], top[1], top[2], 90, 6)
                    && a.landSD(top[0], top[1]) > 80;
                if (summitOk && shore == null) {
                    x = top[0];
                    z = top[1];
                    hc = top[2];
                }
                if (summitOk && shore == null) {
                    kind = Plot.Kind.HILLTOP;
                    size = pick < 0.35 ? 36 : 28;
                } else if (shore != null) {
                    kind = Plot.Kind.LAKESIDE;
                    double ang = Math.atan2(z - shore.cz, x - shore.cx);
                    size = pick < 0.5 ? 20 : 28;
                    double rr = shore.r * 1.3 + 8 + size / 2.0;
                    x = (int) (shore.cx + Math.cos(ang) * rr);
                    z = (int) (shore.cz + Math.sin(ang) * rr);
                } else if (c.mountain > 0.35) {
                    kind = Plot.Kind.MOUNTAIN;
                    size = pick < 0.5 ? 28 : 20;
                } else if (sd < 160) {
                    kind = Plot.Kind.COAST;
                    size = pick < 0.5 ? 20 : 28;
                } else if (c.forest > 0.55) {
                    kind = Plot.Kind.FOREST;
                    size = pick < 0.5 ? 14 : 20;
                } else {
                    if (pick > 0.7) continue;
                    kind = Plot.Kind.MEADOW;
                    size = pick < 0.3 ? 14 : (pick < 0.55 ? 20 : 28);
                }
                Plot p = tryPlot(w, roads, occ, lakes, kind, x, z, size, kind == Plot.Kind.HILLTOP);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    private static Plot tryPlot(AotWorld w, RoadNetwork roads, Occupancy occ, List<Lake> lakes, Plot.Kind kind,
                                int x, int z, int size, boolean hilltop) {
        Atlas a = w.atlas;
        int half = size / 2 + 1;
        int x0 = x - half, z0 = z - half, x1 = x0 + size + 1, z1 = z0 + size + 1;
        if (occ.blocked(x, z, half + 25)) return null;
        if (a.landSD(x, z) < half + 25) return null;
        double[] tmp = new double[1];
        int hmin = Integer.MAX_VALUE, hmax = Integer.MIN_VALUE, sum = 0;
        int[][] pts = {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}, {x, z}};
        for (int[] p : pts) {
            if (a.wallFlatten(p[0], p[1]) > 0 || a.districtAt(p[0], p[1]) != null) return null;
            if (a.riverIndex.query(p[0], p[1], tmp) < River.Index.INFLUENCE + 8) return null;
            if (roads.clearance(p[0], p[1]) < 8) return null;
            for (Lake l : lakes) if (l.norm(p[0], p[1]) < 1.25) return null;
            int hh = w.terrain.naturalHeight(p[0], p[1]);
            hmin = Math.min(hmin, hh);
            hmax = Math.max(hmax, hh);
            sum += hh;
        }
        if (hmax - hmin > (hilltop ? 22 : 14) || hmin <= WorldSpec.SEA + 1) return null;
        int y = hilltop ? w.terrain.naturalHeight(x, z) : Math.round(sum / 5f);
        occ.add(x, z, half + 30);
        return new Plot(kind, x0, z0, x1, z1, y, Style.S);
    }

    /** Points the plot gate at the nearest road and lays a driveway to it. */
    static void driveway(AotWorld w, RoadNetwork roads, Plot p, long seed) {
        double[] r = roads.nearestPoint(p.cx(), p.cz(), false, p.kind == Plot.Kind.HILLTOP ? 700 : 450);
        if (r == null) return;
        double dx = r[0] - p.cx(), dz = r[1] - p.cz();
        p.gate = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Style.E : Style.W) : (dz > 0 ? Style.S : Style.N);
        int[] g = p.gateOut();
        Road road = Road.curve(Road.Type.PATH, g[0], g[1], r[0], r[1], seed);
        if (routeOk(w, road, p)) roads.add(road);
    }

    static boolean routeOk(AotWorld w, Road road, Object exclude) {
        Atlas a = w.atlas;
        int ring0 = -1;
        for (int i = 0; i < road.xs.length; i++) {
            double x = road.xs[i], z = road.zs[i];
            if (a.landSD(x, z) < 6) return false;
            if (a.wallFlatten(x, z) > 0.02 && i > 1 && i < road.xs.length - 2) return false;
            if (a.districtAt(x, z) != null) return false;
            if (w.lakes != null) for (Lake l : w.lakes) if (l.norm(x, z) < 1.2) return false;
            int land = a.landmass(x, z);
            int ring = land == Column.PARADIS ? a.ring(x, z) : 9;
            if (ring0 < 0) ring0 = ring;
            else if (ring != ring0) return false;
        }
        return true;
    }

    /** Trails from each village to the main network and to its nearest neighbour. */
    static void villageTrails(AotWorld w, RoadNetwork roads, List<Village> villages) {
        List<Road> add = new ArrayList<>();
        for (Village v : villages) {
            double[] p = roads.nearestPoint(v.cx, v.cz, true, 4000);
            if (p != null) {
                double d = p[2];
                double sx = v.cx + (p[0] - v.cx) / d * (v.radius - 4), sz = v.cz + (p[1] - v.cz) / d * (v.radius - 4);
                Road r = Road.curve(Road.Type.TRAIL, sx, sz, p[0], p[1], Hash.of(w.spec.seed, v.cx, v.cz, 1));
                if (routeOk(w, r, v)) add.add(r);
            }
            Village best = null;
            double bd = 1700;
            for (Village o : villages) {
                if (o == v) continue;
                double d = Mth.dist(v.cx, v.cz, o.cx, o.cz);
                if (d < bd) {
                    bd = d;
                    best = o;
                }
            }
            if (best != null && (v.cx < best.cx || (v.cx == best.cx && v.cz < best.cz) || !nearestOf(best, v, villages))) {
                double ang = Math.atan2(best.cz - v.cz, best.cx - v.cx);
                Road r = Road.curve(Road.Type.TRAIL, v.cx + Math.cos(ang) * (v.radius - 4), v.cz + Math.sin(ang) * (v.radius - 4),
                    best.cx - Math.cos(ang) * (best.radius - 4), best.cz - Math.sin(ang) * (best.radius - 4), Hash.of(w.spec.seed, v.cx, best.cz, 2));
                if (routeOk(w, r, v)) add.add(r);
            }
        }
        for (Road r : add) roads.add(r);
    }

    private static boolean nearestOf(Village a, Village b, List<Village> all) {
        double d = Mth.dist(a.cx, a.cz, b.cx, b.cz);
        for (Village o : all) if (o != a && o != b && Mth.dist(a.cx, a.cz, o.cx, o.cz) < d) return false;
        return true;
    }

    /** Trails from caves, camps and cabins to the network. */
    static void poiTrails(AotWorld w, RoadNetwork roads, List<Poi> pois) {
        List<Road> add = new ArrayList<>();
        for (Poi p : pois) {
            if (p.kind == Poi.Kind.SHIPWRECK || p.kind == Poi.Kind.SHRINE) continue;
            int[] e = p.entrance();
            double[] r = roads.nearestPoint(e[0], e[1], false, 600);
            if (r == null) continue;
            Road.Type t = p.kind == Poi.Kind.EXPEDITION_CAMP ? Road.Type.TRAIL : Road.Type.PATH;
            double sx = e[0], sz = e[1];
            if (p.kind == Poi.Kind.EXPEDITION_CAMP) {
                double ang = Math.atan2(r[1] - p.z, r[0] - p.x);
                p.openAngle = ang;
                sx = p.x + Math.cos(ang) * 36;
                sz = p.z + Math.sin(ang) * 36;
                add.add(Road.straight(t, p.x + Math.cos(ang) * 24, p.z + Math.sin(ang) * 24, sx, sz));
            }
            Road road = Road.curve(t, sx, sz, r[0], r[1], Hash.of(w.spec.seed, p.x, p.z, 3));
            if (routeOk(w, road, p)) add.add(road);
        }
        for (Road r : add) roads.add(r);
    }
}
