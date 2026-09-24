package com.pglol.aotworld.core.build;

/** A feature with a Stable Master: where to find them. */
public interface StableOwner {
    /** World x/z of the Stable Master, or null. */
    int[] stableSpot();
}
