package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;

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
            // Overhang only.
            if (!s.flat && c >= cx0 - 1 && c <= cx1 + 1 && a >= ax0 - 1 && a <= ax1 + 1) roof(b, x, z, c, top);
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
            } else if (s.flat ? (fl >= 1 && fl <= 2 && p % 2 == 0 && p >= 2 && p <= len - 2)
                              : (fl == 2 && p % 3 == 2 && p <= len - 2)) {
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
    }
}
