package com.pglol.aotworld.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Uniform grid bucketing items by bounding box. Built once, then read concurrently. */
public final class SpatialIndex<T> {
    private final int cell;
    private final Map<Long, List<T>> map = new HashMap<>();

    public SpatialIndex(int cell) {
        this.cell = cell;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    public void add(T item, double minX, double minZ, double maxX, double maxZ) {
        for (int cx = Mth.floor(minX / cell); cx <= Mth.floor(maxX / cell); cx++) {
            for (int cz = Mth.floor(minZ / cell); cz <= Mth.floor(maxZ / cell); cz++) {
                map.computeIfAbsent(key(cx, cz), k -> new ArrayList<>(2)).add(item);
            }
        }
    }

    public List<T> at(double x, double z) {
        List<T> l = map.get(key(Mth.floor(x / cell), Mth.floor(z / cell)));
        return l == null ? Collections.emptyList() : l;
    }
}
