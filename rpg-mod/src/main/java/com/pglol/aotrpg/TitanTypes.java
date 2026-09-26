package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** The AoT mod's titans, sorted into ordinary titans and shifters (and the ones we never spawn). */
public final class TitanTypes {
    private TitanTypes() {}

    private static final String[] SHIFTERS = {"attack", "armored", "colossal", "female", "beast", "cart", "jaw", "warhammer", "founding"};
    /** Joke titans that don't belong in the world. */
    private static final String[] BANNED = {"triple_t"};

    public static boolean banned(String path) {
        for (String b : BANNED) if (path.contains(b)) return true;
        return false;
    }

    public static boolean banned(Entity e) {
        return banned(Registries.ENTITY_TYPE.getId(e.getType()).getPath());
    }

    private static boolean part(String path) {
        return path.contains("nape") || path.contains("eye") || path.contains("grab") || path.contains("hand") || path.contains("leg")
            || path.contains("dummy") || path.contains("shell") || path.contains("shifter") || path.contains("spawn_egg");
    }

    private static boolean shifter(String path) {
        for (String s : SHIFTERS) if (path.contains(s)) return true;
        return false;
    }

    private static List<EntityType<?>> collect(boolean shifters) {
        List<EntityType<?>> out = new ArrayList<>();
        for (Identifier id : Registries.ENTITY_TYPE.getIds()) {
            String path = id.getPath();
            if (!id.getNamespace().equals(AotItems.namespace) || !path.contains("titan") || part(path) || banned(path)) continue;
            if (shifter(path) == shifters) out.add(Registries.ENTITY_TYPE.get(id));
        }
        return out;
    }

    /** Ordinary (pure) titans. */
    public static List<EntityType<?>> ordinary() {
        return collect(false);
    }

    /** Shifter titans (the bosses). */
    public static List<EntityType<?>> shifters() {
        return collect(true);
    }

    /** A shifter by name part ("armored", "female"...), or null. */
    public static EntityType<?> shifter(String which) {
        for (EntityType<?> t : shifters()) if (Registries.ENTITY_TYPE.getId(t).getPath().contains(which)) return t;
        return null;
    }
}
