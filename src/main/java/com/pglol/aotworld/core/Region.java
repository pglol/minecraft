package com.pglol.aotworld.core;

/** A named RPG zone with a level band, shown to players when they enter it. */
public final class Region {
    public interface Shape {
        boolean contains(double x, double z);
    }

    public final String name;
    public final String subtitle;
    public final int minLevel, maxLevel;
    public final int priority;
    /** Level of titans that roam here, 0 for none. */
    public final int titanLevel;
    public final Shape shape;
    public final int warpX, warpZ;
    public int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;

    public Region(String name, String subtitle, int minLevel, int maxLevel, int priority, int titanLevel,
                  int warpX, int warpZ, Shape shape) {
        this.name = name;
        this.subtitle = subtitle;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        this.priority = priority;
        this.titanLevel = titanLevel;
        this.warpX = warpX;
        this.warpZ = warpZ;
        this.shape = shape;
    }

    public Region yRange(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
        return this;
    }

    public boolean contains(double x, double y, double z) {
        return y >= minY && y <= maxY && shape.contains(x, z);
    }

    public String levelText() {
        return minLevel == maxLevel ? "Lv. " + minLevel : "Lv. " + minLevel + "-" + maxLevel;
    }

    /** Lower-case, dash separated id for commands. */
    public String id() {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
