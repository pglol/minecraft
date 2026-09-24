package com.pglol.aotrpg;

import java.util.Random;
import java.util.regex.Pattern;

/** Character name rules and the family names that can be rolled. */
public final class Names {
    private Names() {}

    public static final Pattern PART = Pattern.compile("[A-Za-z][A-Za-z'-]{1,13}");

    /** Walled-in, German-flavoured surnames. The story families (Jaeger, Ackerman, Reiss, Fritz) are left out on purpose. */
    public static final String[] FAMILIES = {
        "Adler", "Albrecht", "Bauer", "Berner", "Brandt", "Braun", "Dietrich", "Eckhart", "Engel", "Falk",
        "Fuchs", "Graf", "Haas", "Hartmann", "Hesse", "Holt", "Jung", "Kaiser", "Keller", "Kessler",
        "Krause", "Kruger", "Lang", "Lenz", "Lindt", "Mohr", "Nagel", "Reinhardt", "Richter", "Roth",
        "Sauer", "Schafer", "Sommer", "Stark", "Vogel", "Voss", "Weber", "Winter", "Wolff", "Zimmer",
        "Aldric", "Baumann", "Eberhart", "Frey", "Gerber", "Hauser", "Kranz", "Lorenz", "Marx", "Oberst",
        "Pfeiffer", "Rausch", "Seidel", "Thal", "Ulbrecht", "Vance", "Wagner", "Ziegler", "Moser", "Kirsch"
    };

    private static final Random RNG = new Random();

    public static String rollFamily(String previous) {
        String f;
        do f = FAMILIES[RNG.nextInt(FAMILIES.length)]; while (f.equals(previous));
        return f;
    }

    /** "anna" -> "Anna". Returns null if it is not a valid name part. */
    public static String clean(String raw) {
        String s = raw.trim();
        if (!PART.matcher(s).matches()) return null;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
