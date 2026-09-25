package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cosmetics: looks only, never power. Operators grant them per player or put players on the
 * allowlist (everything unlocked). Operators always have everything. Saved in aot_rpg/cosmetics.json.
 */
public final class Cosmetics {
    public record Def(String id, String slot, String title, boolean free) { }

    public static final List<Def> ALL = List.of(
        new Def("trail_tracer", "trail", "Tracer", true),
        new Def("trail_ember", "trail", "Ember", false),
        new Def("trail_frost", "trail", "Frost", false),
        new Def("trail_rainbow", "trail", "Rainbow", false),
        new Def("trail_confetti", "trail", "Confetti", false),
        new Def("trail_hearts", "trail", "Hearts", false),
        new Def("trail_void", "trail", "Void", false),
        new Def("trail_lightning", "trail", "Lightning", false));

    private static final class Data {
        Set<String> allowlist = new LinkedHashSet<>();
        Map<String, Set<String>> grants = new HashMap<>();
        Map<String, Map<String, String>> selected = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();
    private Path file;
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("cosmetics.json");
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read cosmetics.json", e);
        }
        if (data == null) data = new Data();
        if (data.allowlist == null) data.allowlist = new LinkedHashSet<>();
        if (data.grants == null) data.grants = new HashMap<>();
        if (data.selected == null) data.selected = new HashMap<>();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save cosmetics.json", e);
        }
    }

    public static Def def(String id) {
        for (Def d : ALL) if (d.id().equals(id)) return d;
        return null;
    }

    public boolean allowlisted(ServerPlayerEntity p) {
        return data.allowlist.contains(p.getUuidAsString()) || server.getPlayerManager().isOperator(p.getGameProfile());
    }

    public Set<String> unlocked(ServerPlayerEntity p) {
        Set<String> out = new LinkedHashSet<>();
        boolean all = allowlisted(p);
        Set<String> g = data.grants.getOrDefault(p.getUuidAsString(), Set.of());
        for (Def d : ALL) if (d.free() || all || g.contains(d.id())) out.add(d.id());
        return out;
    }

    public String selected(ServerPlayerEntity p, String slot) {
        String s = data.selected.getOrDefault(p.getUuidAsString(), Map.of()).get(slot);
        if (s != null && unlocked(p).contains(s)) return s;
        for (Def d : ALL) if (d.slot().equals(slot) && d.free()) return d.id();
        return "";
    }

    public void select(ServerPlayerEntity p, String id) {
        Def d = def(id);
        if (d == null || !unlocked(p).contains(id)) return;
        data.selected.computeIfAbsent(p.getUuidAsString(), k -> new HashMap<>()).put(d.slot(), id);
        save();
        sync(p);
    }

    public void allow(ServerPlayerEntity p, boolean on) {
        if (on) data.allowlist.add(p.getUuidAsString());
        else data.allowlist.remove(p.getUuidAsString());
        save();
        sync(p);
    }

    /** id may be "all". */
    public void grant(ServerPlayerEntity p, String id, boolean on) {
        Set<String> g = data.grants.computeIfAbsent(p.getUuidAsString(), k -> new LinkedHashSet<>());
        for (Def d : ALL) {
            if (!id.equals("all") && !d.id().equals(id)) continue;
            if (on) g.add(d.id());
            else g.remove(d.id());
        }
        save();
        sync(p);
    }

    public void sync(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.CosmeticsSync.ID)) return;
        List<String> sel = new ArrayList<>();
        sel.add(selected(p, "trail"));
        ServerPlayNetworking.send(p, new Net.CosmeticsSync(new ArrayList<>(unlocked(p)), sel, allowlisted(p)));
    }
}
