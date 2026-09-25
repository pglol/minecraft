package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Game modes a character can switch between from the pause menu once unlocked by the story.
 * The list and each mode's required chapter live in <world>/aot_rpg/modes.json, so new modes
 * can be added as the story grows; "soon" modes show but can't be chosen yet.
 */
public final class GameModes {
    public static final class Mode {
        public String id;
        public String title;
        public String desc;
        public int unlockChapter;
        public boolean soon;

        Mode(String id, String title, String desc, int unlockChapter, boolean soon) {
            this.id = id;
            this.title = title;
            this.desc = desc;
            this.unlockChapter = unlockChapter;
            this.soon = soon;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private List<Mode> modes = defaults();

    private static List<Mode> defaults() {
        List<Mode> m = new ArrayList<>();
        m.add(new Mode("story", "Story", "The main campaign. Your gear is protected when you die; your satchel is always safe.", 0, false));
        m.add(new Mode("extraction", "Extraction", "High stakes: you drop your gear when you die, and titans drop rarer loot.", 3, false));
        m.add(new Mode("expedition", "Expedition", "Long-range missions beyond the walls with your squad.", 4, true));
        m.add(new Mode("ironblood", "Ironblood", "One life for this character. The walls remember the fallen.", 5, true));
        return m;
    }

    public void open(MinecraftServer server) {
        Path f = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("modes.json");
        try {
            if (Files.exists(f)) {
                List<Mode> read = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Mode>>() { }.getType());
                if (read != null && !read.isEmpty()) modes = read;
            } else {
                Files.createDirectories(f.getParent());
                Files.writeString(f, GSON.toJson(modes), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read modes.json", e);
        }
    }

    private Mode find(String id) {
        for (Mode m : modes) if (m.id.equals(id)) return m;
        return null;
    }

    public boolean unlocked(Profile pr, Mode m) {
        return !m.soon && (pr.chapter >= m.unlockChapter || pr.unlockedModes.contains(m.id));
    }

    public void choose(ServerPlayerEntity p, String id) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        Mode m = find(id);
        if (m == null || !pr.created) return;
        if (!unlocked(pr, m)) {
            p.sendMessage(Text.literal("Locked: reach Chapter " + m.unlockChapter + " of the story.").formatted(Formatting.RED), true);
            return;
        }
        if (p.getRecentDamageSource() != null) {
            p.sendMessage(Text.literal("You can't change mode in the middle of a fight.").formatted(Formatting.RED), true);
            return;
        }
        pr.mode = m.id;
        AotRpg.PROFILES.save(p.getUuid());
        p.sendMessage(Text.literal("Game mode: " + m.title).formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal("  ·  " + m.desc).formatted(Formatting.GRAY)), false);
        send(p, false);
    }

    public void unlock(ServerPlayerEntity p, String id) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        pr.unlockedModes.add(id);
        AotRpg.PROFILES.save(p.getUuid());
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.ModeView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        List<Net.ModeEntry> list = new ArrayList<>();
        for (Mode m : modes) {
            String req = m.soon ? "Coming soon" : unlocked(pr, m) ? "" : "Unlocks in Chapter " + m.unlockChapter;
            list.add(new Net.ModeEntry(m.id, m.title, m.desc, unlocked(pr, m), m.id.equals(pr.mode), req));
        }
        ServerPlayNetworking.send(p, new Net.ModeView(list, pr.chapter, open));
    }
}
