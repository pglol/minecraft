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

    /** Every cosmetic slot, in menu order. */
    public static final List<String> SLOTS = List.of("trail", "slash", "body", "odm", "horse", "head", "block", "clash");

    public static final List<Def> ALL = List.of(
        new Def("slash_steel", "slash", "Steel", true),
        new Def("slash_crimson", "slash", "Crimson", false),
        new Def("slash_frost", "slash", "Frostbite", false),
        new Def("slash_ember", "slash", "Ember", false),
        new Def("slash_void", "slash", "Void Rend", false),
        new Def("slash_holy", "slash", "Radiant", false),
        new Def("body_none", "body", "None", true),
        new Def("body_embers", "body", "Embers", false),
        new Def("body_frost", "body", "Frost Aura", false),
        new Def("body_sparkle", "body", "Starlight", false),
        new Def("body_petals", "body", "Petals", false),
        new Def("body_soul", "body", "Soul Fire", false),
        new Def("body_void", "body", "Void Mist", false),
        new Def("odm_wind", "odm", "Wind", true),
        new Def("odm_ember", "odm", "Afterburn", false),
        new Def("odm_frost", "odm", "Frost Wake", false),
        new Def("odm_rainbow", "odm", "Prism", false),
        new Def("odm_lightning", "odm", "Thunder Dash", false),
        new Def("odm_void", "odm", "Shadow Step", false),
        new Def("horse_dust", "horse", "Dust", true),
        new Def("horse_ember", "horse", "Hellfire Hooves", false),
        new Def("horse_frost", "horse", "Frost Hooves", false),
        new Def("horse_petal", "horse", "Blossom Gallop", false),
        new Def("horse_soul", "horse", "Phantom Rider", false),
        new Def("head_none", "head", "None", true),
        new Def("head_halo", "head", "Halo", false),
        new Def("head_crown", "head", "Crown", false),
        new Def("head_planets", "head", "Planets", false),
        new Def("head_orbs", "head", "Wisp Orbs", false),
        new Def("head_embers", "head", "Ember Ring", false),
        new Def("block_steel", "block", "Steel Sparks", true),
        new Def("block_frost", "block", "Ice Ward", false),
        new Def("block_ember", "block", "Fire Ward", false),
        new Def("block_holy", "block", "Radiant Ward", false),
        new Def("block_void", "block", "Void Ward", false),
        new Def("block_thunder", "block", "Storm Ward", false),
        new Def("clash_steel", "clash", "Steel", true),
        new Def("clash_frost", "clash", "Shatter", false),
        new Def("clash_ember", "clash", "Inferno", false),
        new Def("clash_holy", "clash", "Radiance", false),
        new Def("clash_void", "clash", "Rift", false),
        new Def("clash_thunder", "clash", "Thunderclap", false),
        new Def("clash_petal", "clash", "Blossom", false),
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
        broadcast(p);
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

    /** What this player wears, as "slot=id" for every slot. */
    public List<String> worn(ServerPlayerEntity p) {
        List<String> out = new ArrayList<>();
        for (String slot : SLOTS) out.add(slot + "=" + selected(p, slot));
        return out;
    }

    /** Tells everyone what this player wears (others' effects are drawn by their clients). */
    public void broadcast(ServerPlayerEntity p) {
        Net.CosmeticsOf msg = new Net.CosmeticsOf(p.getUuid(), worn(p));
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(o, Net.CosmeticsOf.ID)) ServerPlayNetworking.send(o, msg);
        }
    }

    /** A joining player learns what everyone wears. */
    public void sendAll(ServerPlayerEntity to) {
        if (!ServerPlayNetworking.canSend(to, Net.CosmeticsOf.ID)) return;
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) ServerPlayNetworking.send(to, new Net.CosmeticsOf(o.getUuid(), worn(o)));
    }

    public void sync(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.CosmeticsSync.ID)) return;
        List<String> sel = worn(p);
        ServerPlayNetworking.send(p, new Net.CosmeticsSync(new ArrayList<>(unlocked(p)), sel, allowlisted(p)));
    }
}
