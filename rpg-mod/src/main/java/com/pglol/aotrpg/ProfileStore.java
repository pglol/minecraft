package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accounts and their characters, in <world>/aot_rpg/players/.
 *   <uuid>.account.json   which character slots exist, the active one, Gold
 *   <uuid>.json           character in slot 0 (the original single-character file)
 *   <uuid>.<n>.json       characters in other slots
 * get(uuid) always returns the active character, so the rest of the mod needs no changes.
 */
public final class ProfileStore {
    public static final int MAX_CHARACTERS = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Per player account. */
    public static final class Account {
        public int active;
        public List<Integer> slots = new ArrayList<>();
        /** Premium currency for cosmetics and the battle pass (account wide). */
        public long gold;
        public Map<Integer, Long> lastPlayed = new java.util.HashMap<>();
        // Battle pass (account wide, reset when the season id changes).
        public String passSeason = "";
        public long passXp;
        public boolean passPremium;
        public List<Integer> passFree = new ArrayList<>();
        public List<Integer> passPrem = new ArrayList<>();
        public long lastDaily;
        // Special event shop (reset when the event id changes).
        public String eventId = "";
        public long eventTokens;
        public Map<String, Integer> eventBought = new java.util.HashMap<>();
        // Social: friends by uuid, with the last known name.
        public Map<String, String> friends = new java.util.LinkedHashMap<>();
    }

    private final Map<UUID, Profile> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private Path dir;

    public void open(MinecraftServer server) {
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("players");
        cache.clear();
        accounts.clear();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            AotRpg.LOG.error("Could not create profile folder {}", dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    // ------------------------------------------------------------------ accounts

    public Account account(UUID id) {
        return accounts.computeIfAbsent(id, this::loadAccount);
    }

    private Account loadAccount(UUID id) {
        Path f = dir.resolve(id + ".account.json");
        Account a = null;
        if (Files.exists(f)) {
            try {
                a = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Account.class);
            } catch (Exception e) {
                AotRpg.LOG.error("Could not read account {}", f, e);
            }
        }
        if (a == null) a = new Account();
        if (a.slots == null) a.slots = new ArrayList<>();
        if (a.lastPlayed == null) a.lastPlayed = new java.util.HashMap<>();
        if (a.passSeason == null) a.passSeason = "";
        if (a.passFree == null) a.passFree = new ArrayList<>();
        if (a.passPrem == null) a.passPrem = new ArrayList<>();
        if (a.eventId == null) a.eventId = "";
        if (a.eventBought == null) a.eventBought = new java.util.HashMap<>();
        if (a.friends == null) a.friends = new java.util.LinkedHashMap<>();
        if (a.slots.isEmpty()) a.slots.add(0); // everyone has slot 0 (the old single character)
        if (!a.slots.contains(a.active)) a.active = a.slots.get(0);
        return a;
    }

    public void saveAccount(UUID id) {
        Account a = accounts.get(id);
        if (a == null || dir == null) return;
        try {
            Files.writeString(dir.resolve(id + ".account.json"), GSON.toJson(a), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AotRpg.LOG.error("Could not save account {}", id, e);
        }
    }

    /** File name stem for a character: "<uuid>" for slot 0, "<uuid>.<n>" otherwise. */
    public static String stem(UUID id, int slot) {
        return slot == 0 ? id.toString() : id + "." + slot;
    }

    /** The active character's stem, used for per-character files (satchel, state). */
    public String activeStem(UUID id) {
        return stem(id, account(id).active);
    }

    // ------------------------------------------------------------------ characters

    public Profile get(UUID id) {
        return cache.computeIfAbsent(id, k -> read(k, account(k).active));
    }

    /** Reads any slot's character without making it active (for the character list). */
    public Profile peek(UUID id, int slot) {
        if (slot == account(id).active) return get(id);
        return read(id, slot);
    }

    private Profile read(UUID id, int slot) {
        Path f = dir.resolve(stem(id, slot) + ".json");
        if (Files.exists(f)) {
            try {
                Profile p = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Profile.class);
                if (p != null) {
                    if (p.stats == null) p.stats = new java.util.EnumMap<>(Stat.class);
                    if (p.quests == null) p.quests = new java.util.HashMap<>();
                    if (p.tracked == null) p.tracked = "main";
                    if (p.mode == null) p.mode = "story";
                    if (p.skills == null) {
                        // Profile from before skills existed: grant the points they would have earned.
                        p.skills = new java.util.HashSet<>();
                        if (p.created) p.skillPoints = Skill.pointsForLevel(p.level);
                    }
                    if (p.lifestyle == null) p.lifestyle = new java.util.HashMap<>();
                    if (!p.skillsV2) {
                        // The skill trees were rebuilt: every character gets all points back to spend again.
                        p.skills.clear();
                        if (p.created) p.skillPoints = Skill.pointsForLevel(p.level);
                        p.skillsV2 = true;
                    }
                    if (p.orders == null) p.orders = new java.util.HashMap<>();
                    if (p.faction == null) p.faction = "";
                    if (p.unlockedModes == null) p.unlockedModes = new java.util.HashSet<>();
                    if (p.role == null) p.role = "";
                    return p;
                }
            } catch (Exception e) {
                AotRpg.LOG.error("Could not read profile {}", f, e);
            }
        }
        return new Profile();
    }

    public void save(UUID id) {
        Profile p = cache.get(id);
        if (p == null || dir == null) return;
        try {
            Files.writeString(dir.resolve(activeStem(id) + ".json"), GSON.toJson(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AotRpg.LOG.error("Could not save profile {}", id, e);
        }
        saveAccount(id);
    }

    /** Wipes the active character. keepKit: the new character does not get a second starter kit. */
    public void reset(UUID id, boolean keepKit) {
        Profile p = new Profile();
        p.kitGiven = keepKit;
        cache.put(id, p);
        save(id);
    }

    /** Makes another slot active (the caller swaps the player's inventory and state). */
    public void activate(UUID id, int slot) {
        save(id);
        Account a = account(id);
        a.active = slot;
        a.lastPlayed.put(slot, System.currentTimeMillis());
        cache.remove(id);
        get(id);
        saveAccount(id);
    }

    /** A new empty slot, or -1 when the account is full. */
    public int newSlot(UUID id) {
        Account a = account(id);
        if (a.slots.size() >= MAX_CHARACTERS) return -1;
        int n = 0;
        while (a.slots.contains(n)) n++;
        a.slots.add(n);
        java.util.Collections.sort(a.slots);
        saveAccount(id);
        return n;
    }

    /** Forgets a slot's files (not the active one). */
    public void delete(UUID id, int slot) {
        Account a = account(id);
        if (slot == a.active) return;
        a.slots.remove(Integer.valueOf(slot));
        a.lastPlayed.remove(slot);
        saveAccount(id);
        String s = stem(id, slot);
        for (Path f : new Path[] {dir.resolve(s + ".json"), dir.resolve(s + ".state.dat")}) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                AotRpg.LOG.error("Could not delete {}", f, e);
            }
        }
    }

    public void saveAll() {
        for (UUID id : cache.keySet()) save(id);
    }

    public void unload(UUID id) {
        save(id);
        cache.remove(id);
        accounts.remove(id);
    }
}
