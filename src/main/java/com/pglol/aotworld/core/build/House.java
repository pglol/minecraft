package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Hash;

/**
 * A rectangular building rendered one column at a time. The ridge of a pitched
 * roof runs along the "a" axis; walls with the door face the "c" axis.
 */
public final class House {
    public final int x0, z0, x1, z1;
    public final boolean alongX;
    public final int baseY, floors;
    public final Style style;
    /** +1 when the door is on the high-c wall, -1 when on the low-c wall. */
    public final int doorSide;
    public final boolean chimney;

    /** What kind of rooms the interior gets. */
    public enum Use { HOME, HALL, BARRACKS, STABLE }

    private Use use = Use.HOME;
    private boolean vendor;

    public House use(Use u) {
        use = u;
        return this;
    }

    /** Puts a Stable Master (horse vendor) inside. */
    public House vendor() {
        vendor = true;
        return this;
    }

    private final int ax0, ax1, cx0, cx1;

    public House(int x0, int z0, int x1, int z1, boolean alongX, int baseY, int floors, Style style,
                 int doorSide, boolean chimney) {
        this.x0 = Math.min(x0, x1);
        this.x1 = Math.max(x0, x1);
        this.z0 = Math.min(z0, z1);
        this.z1 = Math.max(z0, z1);
        this.alongX = alongX;
        this.baseY = baseY;
        this.floors = Math.max(1, floors);
        this.style = style;
        this.doorSide = doorSide;
        this.chimney = chimney;
        if (alongX) {
            ax0 = this.x0; ax1 = this.x1; cx0 = this.z0; cx1 = this.z1;
        } else {
            ax0 = this.z0; ax1 = this.z1; cx0 = this.x0; cx1 = this.x1;
        }
    }

    public House bare() {
        use = Use.STABLE;
        return this;
    }

    /** Includes the one-block roof overhang. */
    public boolean covers(int x, int z) {
        return x >= x0 - 1 && x <= x1 + 1 && z >= z0 - 1 && z <= z1 + 1;
    }

    public boolean footprint(int x, int z) {
        return x >= x0 && x <= x1 && z >= z0 && z <= z1;
    }

    public int wallTop() {
        return baseY + 4 * floors;
    }

    public int roofTop() {
        return wallTop() + (style.flat ? 1 : (cx1 - cx0 + 3) / 2);
    }

    /** World x/z of the block just outside the door. */
    public int[] doorstep() {
        int a = (ax0 + ax1) / 2;
        int c = doorSide > 0 ? cx1 + 1 : cx0 - 1;
        return alongX ? new int[] {a, c} : new int[] {c, a};
    }

    private int acrossFacing(boolean positive) {
        if (alongX) return positive ? Style.S : Style.N;
        return positive ? Style.E : Style.W;
    }

    private int alongFacing(boolean positive) {
        if (alongX) return positive ? Style.E : Style.W;
        return positive ? Style.S : Style.N;
    }

    public void column(ChunkBuffer b, int x, int z, int groundY) {
        if (!covers(x, z) || !b.contains(x, z)) return;
        Style s = style;
        int a = alongX ? x : z;
        int c = alongX ? z : x;
        boolean inA = a >= ax0 && a <= ax1, inC = c >= cx0 && c <= cx1;
        int top = wallTop();

        if (!(inA && inC)) {
            // Overhang only, plus the doorstep and a torch beside the door.
            if (!s.flat && c >= cx0 - 1 && c <= cx1 + 1 && a >= ax0 - 1 && a <= ax1 + 1) roof(b, x, z, c, top);
            boolean front = inA && (doorSide > 0 ? c == cx1 + 1 : c == cx0 - 1);
            int doorA = ax0 + (ax1 - ax0) / 2;
            if (front && a == doorA) {
                if (groundY < baseY) b.fill(x, groundY, baseY, z, s.base);
                b.fill(x, baseY + 1, baseY + 2, z, Blocks.AIR);
            } else if (front && a == doorA + 1) {
                b.set(x, baseY + 2, z, TORCH[acrossFacing(doorSide > 0)]);
            }
            return;
        }

        // Foundation and clearing.
        if (groundY < baseY) b.fill(x, groundY - 2, baseY - 1, z, s.base);
        b.fill(x, baseY + 1, Math.max(roofTop() + 1, groundY), z, Blocks.AIR);

        boolean edgeA = a == ax0 || a == ax1, edgeC = c == cx0 || c == cx1;
        if (edgeA || edgeC) {
            wall(b, x, z, a, c, edgeA, edgeC, top);
        } else {
            interior(b, x, z, a, c, top);
        }

        if (s.flat) {
            b.set(x, top, z, s.roofFull);
            if (edgeA || edgeC) {
                b.set(x, top + 1, z, s.trim);
            }
        } else {
            roof(b, x, z, c, top);
            if (edgeA) {
                int e = Math.min(c - (cx0 - 1), (cx1 + 1) - c);
                b.fill(x, top + 1, top + e - 1, z, s.plaster);
                int mid = (cx0 + cx1) / 2;
                if (c == mid && e >= 4) b.set(x, top + 2, z, alongX ? s.paneZ : s.paneX);
            }
        }

        if (chimney && a == ax0 + 1 && c == cx0 + 1) {
            int ct = roofTop() + 1;
            b.fill(x, baseY + 1, ct, z, Blocks.BRICKS);
            b.set(x, ct + 1, z, Blocks.CAMPFIRE);
        }
    }

    private void roof(ChunkBuffer b, int x, int z, int c, int top) {
        int lo = cx0 - 1, hi = cx1 + 1;
        int e = Math.min(c - lo, hi - c);
        int width = hi - lo + 1;
        int y = top + e;
        if (width % 2 == 1 && e == (width - 1) / 2) {
            b.set(x, y, z, style.roofFull);
        } else {
            int mid2 = lo + hi; // compare 2c with lo+hi to find the side
            b.set(x, y, z, style.roofStair[acrossFacing(2 * c < mid2)]);
        }
    }

    private void wall(ChunkBuffer b, int x, int z, int a, int c, boolean edgeA, boolean edgeC, int top) {
        Style s = style;
        if (edgeA && edgeC) {
            b.fill(x, baseY, top, z, s.frameY);
            return;
        }
        // The wall runs along "a" when c is fixed, and along "c" when a is fixed.
        boolean runsAlongA = edgeC;
        boolean runX = runsAlongA == alongX;
        int beam = runX ? s.frameX : s.frameZ;
        int pane = runX ? s.paneX : s.paneZ;
        int p = runsAlongA ? a - ax0 : c - cx0;
        int len = runsAlongA ? ax1 - ax0 : cx1 - cx0;
        boolean doorWall = edgeC && (doorSide > 0 ? c == cx1 : c == cx0);
        int doorP = (ax1 - ax0) / 2;

        for (int y = baseY; y <= top; y++) {
            int yy = y - baseY, fl = yy % 4;
            int id;
            if (yy == 0) {
                id = s.base;
            } else if (y == top || fl == 0) {
                id = s.timber ? beam : (y == top ? s.trim : s.plaster);
            } else if (doorWall && p == doorP && (yy == 1 || yy == 2)) {
                int f = acrossFacing(doorSide > 0);
                id = s.door[f * 2 + (yy == 2 ? 1 : 0)];
            } else if (!s.timber && !s.flat && (p == 0 || p == len)) {
                id = s.frameY;
            } else if (!(doorWall && Math.abs(p - doorP) <= 1)
                && (s.flat ? (fl >= 1 && p % 2 == 0 && p >= 2 && p <= len - 2)
                           : (fl >= 2 && p % 3 == 2 && p >= 2 && p <= len - 2))) {
                id = pane;
            } else {
                id = s.plaster;
            }
            b.set(x, y, z, id);
        }
    }

    private void interior(ChunkBuffer b, int x, int z, int a, int c, int top) {
        Style s = style;
        boolean ladderCol = floors > 1 && a == ax1 - 1 && c == cx1 - 1;
        for (int k = 0; k < floors; k++) {
            int fy = baseY + 4 * k;
            if (!(ladderCol && k > 0)) b.set(x, fy, z, s.floor);
            boolean lamp = (a - ax0) % 6 == 3 && (c - cx0) % 6 == 3;
            if (lamp && !ladderCol) b.set(x, fy + 3, z, Blocks.LANTERN_HANGING);
        }
        if (!s.flat) b.set(x, top, z, s.floor);
        if (ladderCol) {
            int f = alongFacing(false);
            b.fill(x, baseY + 1, baseY + 4 * (floors - 1), z, s.ladder[f]);
        }
        if (a == ax0 + 1 && c == cx1 - 1) b.set(x, baseY + 1, z, Blocks.BARREL);
        if (a == ax1 - 1 && c == cx0 + 1) b.set(x, baseY + 1, z, Blocks.CRAFTING_TABLE);

        furnish(b, x, z, a, c);
    }

    // ---- Furnishing ------------------------------------------------------------------------

    private enum Room { TABLE, BEDS, SHELVES, STORAGE, KITCHEN, LOUNGE, STUDY, DINING, BUNKS, ARMORY, STALL, SHOP }

    private static final Room[] HOME_GROUND = {Room.KITCHEN, Room.TABLE, Room.LOUNGE, Room.STORAGE, Room.TABLE};
    private static final Room[] HOME_UPPER = {Room.BEDS, Room.BEDS, Room.SHELVES, Room.STORAGE, Room.LOUNGE, Room.STUDY};
    private static final Room[] HALL = {Room.DINING, Room.STUDY, Room.SHELVES, Room.LOUNGE, Room.DINING, Room.STORAGE, Room.TABLE};
    private static final Room[] BARRACKS = {Room.BUNKS, Room.BUNKS, Room.ARMORY, Room.STORAGE, Room.TABLE};

    /** Fills the interior with 3x3 room vignettes on a 5-block grid, leaving walkways between them. */
    private void furnish(ChunkBuffer b, int x, int z, int a, int c) {
        int ia = a - ax0 - 1, ic = c - cx0 - 1;
        int iw = ax1 - ax0 - 1, id = cx1 - cx0 - 1;
        int ca = (ax0 + ax1) / 2, cc = (cx0 + cx1) / 2;
        long hs = Hash.of(x0 * 31L + z0, x1, z1);
        if (iw < 9 && id < 9) {
            // Cottage: table and chairs, bed upstairs (or in the corner), bookshelf, a resident.
            if (use == Use.STABLE) {
                if (ic == 1 && ia % 3 == 1) b.set(x, baseY + 1, z, Blocks.HAY);
                if (ic == id - 2 && ia % 4 == 2) b.mob(x, baseY + 1, z, "horse");
                return;
            }
            int y1 = baseY + 1;
            if (ax1 - ax0 >= 6 && c == cc) {
                if (a == ca) {
                    b.set(x, y1, z, Blocks.OAK_FENCE);
                    b.set(x, y1 + 1, z, PLATE);
                } else if (a == ca - 1) {
                    b.set(x, y1, z, CHAIR[alongFacing(false)]);
                } else if (a == ca + 1) {
                    b.set(x, y1, z, CHAIR[alongFacing(true)]);
                }
            }
            int bedY = baseY + 4 * (floors - 1) + 1;
            int color = (int) Math.floorMod(hs, (long) BED_COLORS.length);
            if (a == ax0 + 1 && c == cx0 + 1) {
                b.set(x, bedY, z, bed(color, acrossFacing(false), true));
                if (floors > 1) b.fill(x, y1, y1 + 1, z, BOOKSHELF);
            } else if (a == ax0 + 1 && c == cx0 + 2) {
                b.set(x, bedY, z, bed(color, acrossFacing(false), false));
            } else if (a == ax1 - 1 && c == cc && id >= 4) {
                b.set(x, y1, z, FURNACE[alongFacing(false)]);
            } else if (a == ca + 1 && c == cc + 1 && Hash.unit(hs) < 0.6) {
                b.mob(x, y1, z, "villager");
            }
            if (vendor && a == ca && c == cc - 1) b.mob(x, y1, z, "aot:stable_master");
            return;
        }
        if (ia < 0 || ic < 0) return;
        int cellA = ia / 5, cellC = ic / 5;
        if (cellA >= iw / 5 || cellC >= id / 5) return; // leftover strip stays a walkway
        int oa = ia % 5 - 1, oc = ic % 5 - 1;           // -1..3; vignettes use 0..2
        int ladderA = ax1 - 1 - ax0 - 1, ladderC = cx1 - 1 - cx0 - 1;
        if (floors > 1 && ladderA / 5 == cellA && ladderC / 5 == cellC) return;
        for (int k = 0; k < floors; k++) {
            int y = baseY + 4 * k + 1;
            long h = Hash.of(hs, k, cellA * 64L + cellC);
            Room room;
            switch (use) {
                case HALL: room = HALL[Hash.range(h, 0, HALL.length - 1)]; break;
                case BARRACKS: room = BARRACKS[Hash.range(h, 0, BARRACKS.length - 1)]; break;
                case STABLE: room = Room.STALL; break;
                default: room = k == 0 ? HOME_GROUND[Hash.range(h, 0, HOME_GROUND.length - 1)]
                                       : HOME_UPPER[Hash.range(h, 0, HOME_UPPER.length - 1)];
            }
            if (vendor && k == 0 && cellA == 0 && cellC == 0) room = Room.SHOP;
            if (oa == -1 && oc == -1) {
                // Walkway corner: sometimes someone is standing there.
                if (k == 0 && use != Use.STABLE && Hash.unit(Hash.mix(h + 9)) < 0.35) b.mob(x, y, z, "villager");
                continue;
            }
            if (oa < 0 || oc < 0 || oa > 2 || oc > 2) continue;
            vignette(b, x, y, z, room, oa, oc, h);
        }
    }

    private void vignette(ChunkBuffer b, int x, int y, int z, Room room, int oa, int oc, long h) {
        int toCNeg = acrossFacing(false), toCPos = acrossFacing(true), toANeg = alongFacing(false), toAPos = alongFacing(true);
        int color = (int) Math.floorMod(h, (long) BED_COLORS.length);
        switch (room) {
            case TABLE:
                if (oa == 1 && oc == 1) { b.set(x, y, z, Blocks.OAK_FENCE); b.set(x, y + 1, z, PLATE); }
                else if (oa == 1 && oc == 0) b.set(x, y, z, CHAIR[toCNeg]);
                else if (oa == 1 && oc == 2) b.set(x, y, z, CHAIR[toCPos]);
                else if (oa == 0 && oc == 1) b.set(x, y, z, CHAIR[toANeg]);
                else if (oa == 2 && oc == 1) b.set(x, y, z, CHAIR[toAPos]);
                else if (oa == 0 && oc == 0) b.set(x, y, z, POTS[color % POTS.length]);
                break;
            case BEDS:
                if ((oa == 0 || oa == 2) && oc <= 1) b.set(x, y, z, bed(color, toCNeg, oc == 0));
                else if (oa == 1 && oc == 0) { b.set(x, y, z, Blocks.BARREL); b.set(x, y + 1, z, Blocks.LANTERN); }
                else if (oa == 1 && oc == 2) b.set(x, y, z, CARPET[color % CARPET.length]);
                break;
            case SHELVES:
                if (oc == 1) b.fill(x, y, y + 1, z, BOOKSHELF);
                else if (oc == 0 && oa == 1) b.set(x, y, z, CHAIR[toCPos]);
                break;
            case STORAGE:
                if (oc == 0) {
                    if (oa == 1) b.lootChest(x, y, z, Style.FACING[toCPos], "minecraft:chests/village/village_plains_house");
                    else b.fill(x, y, y + (oa == 0 ? 1 : 0), z, Blocks.BARREL);
                } else if (oc == 2 && oa == 0) b.set(x, y, z, Blocks.CRAFTING_TABLE);
                else if (oc == 2 && oa == 2) b.set(x, y, z, Blocks.HAY);
                break;
            case KITCHEN:
                if (oc == 0) b.set(x, y, z, oa == 0 ? SMOKER[toCPos] : oa == 1 ? FURNACE[toCPos] : CAULDRON);
                else if (oc == 2) {
                    b.set(x, y, z, oa == 1 ? Blocks.BARREL : COUNTER);
                    if (oa == 0) b.set(x, y + 1, z, POTS[color % POTS.length]);
                }
                break;
            case LOUNGE:
                if (oa == 1 && oc == 1) b.set(x, y, z, POTS[(color + 1) % POTS.length]);
                else b.set(x, y, z, CARPET[color % CARPET.length]);
                if (oa == 0 && oc == 0) b.set(x, y, z, CHAIR[toANeg]);
                if (oa == 2 && oc == 2) b.set(x, y, z, CHAIR[toAPos]);
                break;
            case STUDY:
                if (oc == 0 && oa == 1) b.set(x, y, z, LECTERN[toCPos]);
                else if (oc == 0) b.fill(x, y, y + 1, z, BOOKSHELF);
                else if (oc == 2 && oa == 1) b.set(x, y, z, CHAIR[toCPos]);
                else if (oc == 2 && oa == 2) b.set(x, y, z, CANDLE);
                break;
            case DINING:
                if (oc == 1) { b.set(x, y, z, Blocks.OAK_FENCE); b.set(x, y + 1, z, oa == 1 ? CANDLE : PLATE); }
                else b.set(x, y, z, CHAIR[oc == 0 ? toCNeg : toCPos]);
                break;
            case BUNKS:
                if ((oa == 0 || oa == 2) && oc <= 1) b.set(x, y, z, bed(color, toCNeg, oc == 0));
                else if (oa == 1 && oc == 0) b.lootChest(x, y, z, Style.FACING[toCPos], "minecraft:chests/village/village_weaponsmith");
                break;
            case ARMORY:
                if (oc == 1 && (oa == 0 || oa == 2)) b.mob(x, y, z, "armor_stand");
                else if (oc == 0 && oa == 1) b.set(x, y, z, GRINDSTONE);
                else if (oc == 2 && oa == 1) b.set(x, y, z, ANVIL);
                else if (oc == 0) b.set(x, y, z, Blocks.BARREL);
                break;
            case STALL:
                if (oc == 2 && oa == 0) b.set(x, y, z, Blocks.HAY);
                else if (oc == 2 && oa == 2) b.set(x, y, z, CAULDRON);
                else if (oc == 0 && oa == 1 && Hash.unit(h) < 0.8) b.mob(x, y, z, "horse");
                break;
            case SHOP:
                if (oc == 0) b.set(x, y, z, oa == 1 ? Blocks.BARREL : COUNTER);
                else if (oc == 1 && oa == 1) b.mob(x, y, z, "aot:stable_master");
                else if (oc == 2) b.set(x, y, z, Blocks.HAY);
                break;
            default:
                break;
        }
    }

    private static final String[] BED_COLORS = {"red", "white", "blue", "brown", "green", "light_gray", "cyan"};
    private static final int PLATE = Blocks.id("oak_pressure_plate");
    private static final int BOOKSHELF = Blocks.id("bookshelf");
    private static final int CAULDRON = Blocks.id("water_cauldron[level=3]");
    private static final int COUNTER = Blocks.id("smooth_stone_slab[type=top]");
    private static final int CANDLE = Blocks.id("candle[candles=3,lit=false]");
    private static final int GRINDSTONE = Blocks.id("grindstone[face=floor,facing=north]");
    private static final int ANVIL = Blocks.id("anvil[facing=north]");
    private static final int[] POTS = {Blocks.id("potted_fern"), Blocks.id("potted_poppy"), Blocks.id("potted_cornflower"), Blocks.id("potted_azure_bluet")};
    private static final int[] CARPET = {Blocks.id("red_carpet"), Blocks.id("brown_carpet"), Blocks.id("cyan_carpet"), Blocks.id("green_carpet"), Blocks.id("light_gray_carpet")};
    private static final int[] CHAIR = new int[4], TORCH = new int[4], FURNACE = new int[4], SMOKER = new int[4], LECTERN = new int[4];
    private static final int[][][] BEDS = new int[BED_COLORS.length][4][2];

    static {
        for (int f = 0; f < 4; f++) {
            CHAIR[f] = Blocks.id("oak_stairs[facing=" + Style.FACING[f] + ",half=bottom]");
            TORCH[f] = Blocks.id("wall_torch[facing=" + Style.FACING[f] + "]");
            FURNACE[f] = Blocks.id("furnace[facing=" + Style.FACING[f] + "]");
            SMOKER[f] = Blocks.id("smoker[facing=" + Style.FACING[f] + "]");
            LECTERN[f] = Blocks.id("lectern[facing=" + Style.FACING[f] + "]");
            for (int col = 0; col < BED_COLORS.length; col++) {
                BEDS[col][f][0] = Blocks.id(BED_COLORS[col] + "_bed[facing=" + Style.FACING[f] + ",part=foot]");
                BEDS[col][f][1] = Blocks.id(BED_COLORS[col] + "_bed[facing=" + Style.FACING[f] + ",part=head]");
            }
        }
    }

    private static int bed(int color, int facing, boolean head) {
        return BEDS[color][facing][head ? 1 : 0];
    }
}
