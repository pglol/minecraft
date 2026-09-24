package com.pglol.aotworld.core;

/** Stateless deterministic hashing used for all per-position randomness. */
public final class Hash {
    private Hash() {}

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    public static long of(long seed, long a) {
        return mix(seed ^ mix(a * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L));
    }

    public static long of(long seed, long a, long b) {
        return mix(of(seed, a) ^ (b * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L));
    }

    public static long of(long seed, long a, long b, long c) {
        return mix(of(seed, a, b) ^ (c * 0x27D4EB2F165667C5L + 0x85EBCA77C2B2AE63L));
    }

    /** Uniform double in [0,1). */
    public static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Uniform int in [lo, hi] inclusive. */
    public static int range(long h, int lo, int hi) {
        return lo + (int) Math.floorMod(h, (long) (hi - lo + 1));
    }
}
