package com.pglol.aotrpg;

/** The map's sectors, for markets and faction control. */
public enum Sector {
    SINA("Wall Sina", "Mitras and the inner districts"),
    ROSE("Wall Rose", "Trost, Karanes and the farmland"),
    MARIA("Wall Maria", "Shiganshina and the reclaimed land"),
    BEYOND("Beyond the Walls", "Titan territory"),
    MARLEY("Marley", "Across the sea");

    public final String title, blurb;

    Sector(String title, String blurb) {
        this.title = title;
        this.blurb = blurb;
    }

    public static Sector at(double x, double z) {
        int[] w = AotRpg.PLACES.walls;
        if (AotRpg.PLACES.nearest(x, z, 900, "marley") != null && (w == null || Math.hypot(x, z) > w[2] * 1.3)) return MARLEY;
        if (w == null) return ROSE;
        double d = Math.hypot(x, z);
        if (d <= w[0]) return SINA;
        if (d <= w[1]) return ROSE;
        if (d <= w[2]) return MARIA;
        return BEYOND;
    }
}
