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

    private static final String OPEN_TITLE = "Open World",
        OPEN_DESC = "Paradis is yours to roam: towns, titans, homes, trade. Your gear is kept when you fall.",
        EXTRACT_DESC = "Gear up in the staging hall, drop into titan country with your squad, loot what you can and reach a flare to get out. Fall and it's all left behind.";

    private static List<Mode> defaults() {
        List<Mode> m = new ArrayList<>();
        m.add(new Mode("story", OPEN_TITLE, OPEN_DESC, 0, false));
        m.add(new Mode("extraction", "Extraction", EXTRACT_DESC, 0, false));
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
                // The main mode is the open world now, and Extraction is open to everyone.
                for (Mode m : modes) {
                    if (m.id.equals("story")) {
                        m.title = OPEN_TITLE;
                        m.desc = OPEN_DESC;
                    } else if (m.id.equals("extraction")) {
                        m.desc = EXTRACT_DESC;
                        m.unlockChapter = 0;
                        m.soon = false;
                    }
                }
                Files.writeString(f, GSON.toJson(modes), StandardCharsets.UTF_8);
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
        // The story is off, so modes no longer wait on story chapters: everything ready is open.
        return !m.soon && (!Story.STORY_ENABLED || pr.chapter >= m.unlockChapter || pr.unlockedModes.contains(m.id));
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
        if (Extraction.inRun(p.getUuid())) {
            p.sendMessage(Text.literal("Get out of the zone first.").formatted(Formatting.RED), true);
            return;
        }
        pr.mode = m.id;
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal(m.title).formatted(Formatting.GOLD, Formatting.BOLD), null, 0xE0B96A, "minecraft:compass", null);
        send(p, false);
        // Extraction lives in its own staging hall; leaving it puts you back where you were.
        if (DeathCare.EXTRACTION.equals(m.id) && !Extraction.inLobby(p)) AotRpg.EXTRACT.toLobby(p);
        else if (!DeathCare.EXTRACTION.equals(m.id) && Extraction.inLobby(p)) AotRpg.EXTRACT.toOpenWorld(p);
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
