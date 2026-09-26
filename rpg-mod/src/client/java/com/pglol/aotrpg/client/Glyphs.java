package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;

/**
 * Small hand-drawn pixel icons for the HUD (sun, moon, rain, coin...). Each row is a string:
 * '#' is the main colour, 'o' the second, anything else is empty.
 */
public final class Glyphs {
    private Glyphs() {}

    public static final String[] SUN = {
        "....#....",
        ".#.....#.",
        "...###...",
        "..#####..",
        "#.#####.#",
        "..#####..",
        "...###...",
        ".#.....#.",
        "....#...."};
    public static final String[] MOON = {
        "...###...",
        ".####....",
        ".###.....",
        "###......",
        "###......",
        "###......",
        ".###.....",
        ".####....",
        "...###..."};
    public static final String[] RAIN = {
        ".........",
        "...###...",
        ".######..",
        "########.",
        "########.",
        ".........",
        ".o..o..o.",
        "o..o..o..",
        "........."};
    public static final String[] STORM = {
        ".........",
        "...###...",
        ".######..",
        "########.",
        "########.",
        "....o....",
        "...oo....",
        "....o....",
        "...o....."};
    public static final String[] SNOW = {
        ".........",
        "...###...",
        ".######..",
        "########.",
        "########.",
        ".........",
        ".o...o...",
        "...o...o.",
        ".o...o..."};
    public static final String[] COIN = {
        "..###..",
        ".#ooo#.",
        "#oo#oo#",
        "#o###o#",
        "#oo#oo#",
        ".#ooo#.",
        "..###.."};
    public static final String[] HEART = {
        ".##.##.",
        "#######",
        "#######",
        ".#####.",
        "..###..",
        "...#..."};
    public static final String[] SHIELD = {
        "#######",
        "#ooooo#",
        "#ooooo#",
        ".#ooo#.",
        ".#ooo#.",
        "..#o#..",
        "...#..."};
    public static final String[] BOLT = {
        "...##",
        "..##.",
        ".##..",
        "#####",
        "..##.",
        ".##..",
        "##..."};
    public static final String[] WHEAT = {
        "..#..",
        ".#.#.",
        "..#..",
        ".#.#.",
        "..#..",
        "..#..",
        "..#.."};
    public static final String[] SKULL = {
        ".#####.",
        "#######",
        "#o##o##",
        "#######",
        ".#.#.#."};

    public static void draw(DrawContext c, int x, int y, String[] rows, int main, int second) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int run = -1;
            char runCh = 0;
            for (int i = 0; i <= row.length(); i++) {
                char ch = i < row.length() ? row.charAt(i) : '.';
                if (ch != runCh) {
                    if (run >= 0 && (runCh == '#' || runCh == 'o')) c.fill(x + run, y + r, x + i, y + r + 1, runCh == '#' ? main : second);
                    run = i;
                    runCh = ch;
                }
            }
        }
    }

    public static int width(String[] rows) {
        return rows[0].length();
    }
}
