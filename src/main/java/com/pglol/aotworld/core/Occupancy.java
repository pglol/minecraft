package com.pglol.aotworld.core;

/** Circles already claimed by something, to keep scattered things apart. */
final class Occupancy {
    private final SpatialIndex<double[]> idx = new SpatialIndex<>(256);

    void add(double x, double z, double r) {
        idx.add(new double[] {x, z, r}, x - r, z - r, x + r, z + r);
    }

    boolean blocked(double x, double z, double r) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (double[] c : idx.at(x + dx * r, z + dz * r)) {
                    if (Mth.dist(x, z, c[0], c[1]) < c[2] + r) return true;
                }
            }
        }
        return false;
    }
}
