package com.pglol.aotrpg;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The character creator shown on a player's first join: Origin, Discipline, starting stats,
 * name, confirm. The player is frozen and protected until they finish.
 */
public final class CharacterCreation {
    public static final int START_POINTS = 5;

    private enum Page { ORIGIN, DISCIPLINE, STATS, NAME, CONFIRM }

    private static final class Session {
        Page page = Page.ORIGIN;
        Origin origin;
        Discipline discipline;
        final Map<Stat, Integer> stats = new EnumMap<>(Stat.class);
        String first, family;
        /** 0 = not typing, 1 = typing first name, 2 = typing family name. */
        int awaitingName;
        Vec3d anchor;
        float yaw, pitch;

        int spent() {
            int s = 0;
            for (int v : stats.values()) s += v;
            return s;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    public boolean active(ServerPlayerEntity p) {
        return sessions.containsKey(p.getUuid());
    }

    public void begin(ServerPlayerEntity p) {
        Session s = new Session();
        s.anchor = p.getPos();
        s.yaw = p.getYaw();
        s.pitch = p.getPitch();
        sessions.put(p.getUuid(), s);
        p.setInvulnerable(true);
        if (AotRpg.hasClient(p)) {
            // Players with the mod get the real creator screen.
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Net.OpenCreator(""));
            return;
        }
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 20 * 60 * 30, 0, false, false));
        Titles.show(p, Text.literal("ATTACK ON TITAN").formatted(Formatting.DARK_RED, Formatting.BOLD),
            Text.literal("Create your character").formatted(Formatting.GRAY), 10, 60, 20);
        p.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.MASTER, 1f, 0.6f);
        AotRpg.SCHEDULER.later(50, () -> {
            if (active(p) && !p.isDisconnected()) show(p);
        });
    }

    public void end(ServerPlayerEntity p) {
        sessions.remove(p.getUuid());
    }

    /** Keeps players in creation where they are. */
    public void tick(ServerPlayerEntity p) {
        Session s = sessions.get(p.getUuid());
        if (s == null) return;
        if (p.getPos().squaredDistanceTo(s.anchor) > 0.25) {
            p.networkHandler.requestTeleport(s.anchor.x, s.anchor.y, s.anchor.z, s.yaw, s.pitch);
        }
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
    }

    /** Chat typed while choosing a name. Returns true if the message was consumed. */
    public boolean chat(ServerPlayerEntity p, String msg) {
        Session s = sessions.get(p.getUuid());
        if (s == null || AotRpg.hasClient(p)) return false;
        if (s.awaitingName == 0) {
            p.sendMessage(Text.literal("Finish creating your character first.").formatted(Formatting.GRAY));
            return true;
        }
        String name = Names.clean(msg);
        if (name == null) {
            p.sendMessage(Text.literal("One word, 2-14 letters (' and - allowed). Try again:").formatted(Formatting.RED));
            return true;
        }
        if (s.awaitingName == 1) s.first = name;
        else s.family = name;
        s.awaitingName = 0;
        s.page = Page.NAME;
        AotRpg.SCHEDULER.later(1, () -> show(p));
        return true;
    }

    private void click(ServerPlayerEntity p) {
        p.playSoundToPlayer(SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.MASTER, 0.6f, 1f);
    }

    private void go(ServerPlayerEntity p, Page page) {
        Session s = sessions.get(p.getUuid());
        if (s == null) return;
        s.page = page;
        click(p);
        show(p);
    }

    public void show(ServerPlayerEntity p) {
        Session s = sessions.get(p.getUuid());
        if (s == null || s.awaitingName != 0) return;
        Menu m = switch (s.page) {
            case ORIGIN -> originPage(s);
            case DISCIPLINE -> disciplinePage(s);
            case STATS -> statsPage(s);
            case NAME -> namePage(s);
            case CONFIRM -> confirmPage(s);
        };
        // Esc does not skip creation: the menu comes back.
        m.onClose(pl -> AotRpg.SCHEDULER.later(10, () -> {
            if (active(pl) && !pl.isDisconnected()) show(pl);
        }));
        m.open(p);
    }

    private static Text header(String step, String text) {
        return Text.literal(step + "  ").formatted(Formatting.DARK_GRAY)
            .append(Text.literal(text).formatted(Formatting.DARK_RED, Formatting.BOLD));
    }

    private static ItemStack back() {
        return Menu.icon(Items.ARROW, Menu.line("← Back", Formatting.GRAY));
    }

    private Menu originPage(Session s) {
        Menu m = new Menu(header("1/5", "Where did you grow up?"), 5);
        m.button(4, Menu.icon(Items.WRITABLE_BOOK, Menu.line("Year 845", Formatting.GOLD),
            Menu.line("For a hundred years humanity has lived", Formatting.GRAY),
            Menu.line("behind three great walls: Maria, Rose", Formatting.GRAY),
            Menu.line("and Sina. Beyond them, titans roam.", Formatting.GRAY),
            Text.empty(),
            Menu.line("Choose your Origin.", Formatting.YELLOW)), null);
        int[] slots = {20, 22, 24, 29, 31, 33};
        Origin[] all = Origin.values();
        for (int i = 0; i < all.length; i++) {
            Origin o = all[i];
            ItemStack it = Menu.icon(o.icon, Menu.line(o.title, Formatting.GOLD),
                Menu.line(o.blurb, Formatting.GRAY), Text.empty(),
                Menu.line(o.bonus, Formatting.GREEN), Text.empty(),
                Menu.line("Click to choose", Formatting.YELLOW));
            if (o == s.origin) Menu.glow(it);
            m.button(slots[i], it, p -> {
                s.origin = o;
                go(p, Page.DISCIPLINE);
            });
        }
        return m;
    }

    private Menu disciplinePage(Session s) {
        Menu m = new Menu(header("2/5", "Choose your Discipline"), 5);
        m.button(4, Menu.icon(Items.IRON_SWORD, Menu.line("Discipline", Formatting.GOLD),
            Menu.line("How you fight. Every cadet learns", Formatting.GRAY),
            Menu.line("the blade, but each has a strength.", Formatting.GRAY)), null);
        int[] slots = {20, 22, 24, 30, 32};
        Discipline[] all = Discipline.values();
        for (int i = 0; i < all.length; i++) {
            Discipline d = all[i];
            ItemStack it = Menu.icon(d.icon, Menu.line(d.title, Formatting.GOLD),
                Menu.line(d.blurb, Formatting.GRAY), Text.empty(),
                Menu.line(d.perks, Formatting.GREEN), Text.empty(),
                Menu.line("Click to choose", Formatting.YELLOW));
            if (d == s.discipline) Menu.glow(it);
            m.button(slots[i], it, p -> {
                s.discipline = d;
                go(p, Page.STATS);
            });
        }
        m.button(36, back(), p -> go(p, Page.ORIGIN));
        return m;
    }

    private Menu statsPage(Session s) {
        Menu m = new Menu(header("3/5", "Train your body"), 5);
        int left = START_POINTS - s.spent();
        m.button(4, Menu.icon(Items.EXPERIENCE_BOTTLE, Menu.line(left + " point" + (left == 1 ? "" : "s") + " left", Formatting.GOLD),
            Menu.line("Spread " + START_POINTS + " points across your stats.", Formatting.GRAY),
            Menu.line("Unspent points are kept for later.", Formatting.GRAY),
            Menu.line("You earn 1 more every level.", Formatting.GRAY)), null);
        int[] cols = {1, 3, 5, 7};
        Stat[] all = Stat.values();
        for (int i = 0; i < all.length; i++) {
            Stat st = all[i];
            int v = s.stats.getOrDefault(st, 0);
            int bonus = (s.origin != null && s.origin.bonusStat == st ? 1 : 0)
                + (s.origin == Origin.UNDERGROUND && st == Stat.STRENGTH ? 1 : 0);
            ItemStack it = Menu.icon(st.icon, Menu.line(st.title + ": " + (v + bonus), Formatting.GOLD),
                Menu.line(st.effect, Formatting.GRAY),
                bonus > 0 ? Menu.line("+" + bonus + " from your origin", Formatting.GREEN) : Text.empty());
            it.setCount(Math.max(1, v + bonus));
            m.button(18 + cols[i], it, null);
            if (left > 0) m.button(9 + cols[i], Menu.icon(Items.LIME_STAINED_GLASS_PANE, Menu.line("+1 " + st.title, Formatting.GREEN)), p -> {
                s.stats.merge(st, 1, Integer::sum);
                go(p, Page.STATS);
            });
            if (v > 0) m.button(27 + cols[i], Menu.icon(Items.RED_STAINED_GLASS_PANE, Menu.line("-1 " + st.title, Formatting.RED)), p -> {
                s.stats.merge(st, -1, Integer::sum);
                go(p, Page.STATS);
            });
        }
        m.button(36, back(), p -> go(p, Page.DISCIPLINE));
        m.button(44, Menu.icon(Items.LIME_DYE, Menu.line("Next →", Formatting.GREEN)), p -> go(p, Page.NAME));
        return m;
    }

    private Menu namePage(Session s) {
        if (s.family == null) s.family = Names.rollFamily(null);
        Menu m = new Menu(header("4/5", "What is your name?"), 4);
        String first = s.first == null ? "?" : s.first;
        m.button(4, Menu.icon(Items.NAME_TAG, Menu.line(first + " " + s.family, Formatting.GOLD, Formatting.BOLD),
            Menu.line("Shown above your head to other players.", Formatting.GRAY)), null);
        m.button(10, Menu.icon(Items.PLAYER_HEAD, Menu.line("First name: use my username", Formatting.GOLD),
            Menu.line("Uses the letters of your Minecraft name.", Formatting.GRAY)), p -> {
                String n = Names.clean(p.getGameProfile().getName().replaceAll("[^A-Za-z'-]", ""));
                if (n == null) {
                    p.sendMessage(Text.literal("Your username can't be used as a name, type one instead.").formatted(Formatting.RED));
                    return;
                }
                s.first = n;
                go(p, Page.NAME);
            });
        m.button(12, Menu.icon(Items.WRITABLE_BOOK, Menu.line("First name: type it", Formatting.GOLD),
            Menu.line("Type it in chat after clicking.", Formatting.GRAY)), p -> ask(p, s, 1, "first name"));
        m.button(14, Menu.icon(Items.EMERALD, Menu.line("Family name: roll again \u2684", Formatting.GOLD),
            Menu.line("Current: " + s.family, Formatting.GRAY),
            Menu.line("A random family of the walls.", Formatting.DARK_GRAY)), p -> {
                s.family = Names.rollFamily(s.family);
                go(p, Page.NAME);
            });
        m.button(16, Menu.icon(Items.WRITABLE_BOOK, Menu.line("Family name: type it", Formatting.GOLD),
            Menu.line("Type it in chat after clicking.", Formatting.GRAY)), p -> ask(p, s, 2, "family name"));
        m.button(27, back(), p -> go(p, Page.STATS));
        if (s.first != null) m.button(35, Menu.icon(Items.LIME_DYE, Menu.line("Next \u2192", Formatting.GREEN)), p -> go(p, Page.CONFIRM));
        return m;
    }

    private void ask(ServerPlayerEntity p, Session s, int which, String what) {
        s.awaitingName = which;
        click(p);
        p.closeHandledScreen();
        p.sendMessage(Text.literal("\u270e Type your character's " + what + " in chat:").formatted(Formatting.GOLD, Formatting.BOLD));
    }

    private Menu confirmPage(Session s) {
        Menu m = new Menu(header("5/5", "Your story begins"), 3);
        List<Text> lore = new ArrayList<>();
        lore.add(Menu.line("Origin: " + s.origin.title, Formatting.GRAY));
        lore.add(Menu.line("Discipline: " + s.discipline.title, Formatting.GRAY));
        for (Stat st : Stat.values()) {
            lore.add(Menu.line("  " + st.title + " " + s.stats.getOrDefault(st, 0), Formatting.DARK_GRAY));
        }
        int left = START_POINTS - s.spent();
        if (left > 0) lore.add(Menu.line(left + " unspent point(s) kept", Formatting.DARK_GRAY));
        m.button(13, Menu.icon(Items.PAPER, Menu.line(s.first + " " + s.family, Formatting.GOLD), lore.toArray(Text[]::new)), null);
        m.button(11, Menu.icon(Items.RED_DYE, Menu.line("Start over", Formatting.RED)), p -> {
            s.stats.clear();
            go(p, Page.ORIGIN);
        });
        m.button(15, Menu.glow(Menu.icon(Items.LIME_DYE, Menu.line("Enlist!", Formatting.GREEN, Formatting.BOLD),
            Menu.line("Dedicate your heart.", Formatting.GRAY))), p -> finish(p, s));
        m.button(18, back(), p -> go(p, Page.NAME));
        return m;
    }

    /** The creator screen's answer (players with the mod). */
    public void submit(ServerPlayerEntity p, Net.Create c) {
        Session s = sessions.get(p.getUuid());
        if (s == null) return;
        String error = null;
        String first = Names.clean(c.first()), family = Names.clean(c.family());
        int sum = 0;
        if (c.stats().length != Stat.values().length) error = "Invalid stats.";
        else for (int v : c.stats()) {
            if (v < 0) error = "Invalid stats.";
            sum += v;
        }
        if (sum > START_POINTS) error = "Too many stat points.";
        if (c.origin() < 0 || c.origin() >= Origin.values().length) error = "Choose an origin.";
        if (c.discipline() < 0 || c.discipline() >= Discipline.values().length) error = "Choose a discipline.";
        if (first == null) error = "First name: one word, 2-14 letters.";
        else if (family == null) error = "Family name: one word, 2-14 letters.";
        if (error != null) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Net.OpenCreator(error));
            return;
        }
        s.origin = Origin.values()[c.origin()];
        s.discipline = Discipline.values()[c.discipline()];
        s.stats.clear();
        for (Stat st : Stat.values()) if (c.stats()[st.ordinal()] > 0) s.stats.put(st, c.stats()[st.ordinal()]);
        s.first = first;
        s.family = family;
        finish(p, s);
    }

    private void finish(ServerPlayerEntity p, Session s) {
        sessions.remove(p.getUuid());
        p.closeHandledScreen();
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        pr.created = true;
        pr.firstName = s.first;
        pr.familyName = s.family;
        pr.name = s.first + " " + s.family;
        pr.origin = s.origin;
        pr.discipline = s.discipline;
        pr.level = 1;
        pr.xp = 0;
        pr.stats = new EnumMap<>(s.stats);
        pr.points = START_POINTS - s.spent();
        pr.skillPoints = Skill.pointsForLevel(1);
        pr.skillsV2 = true;
        pr.skills = new java.util.HashSet<>();
        pr.chapter = 1;
        AotRpg.PROFILES.save(p.getUuid());

        p.setInvulnerable(false);
        p.removeStatusEffect(StatusEffects.BLINDNESS);
        AotRpg.PROGRESSION.apply(p, pr);
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);

        int[] home = AotRpg.PLACES.get(s.origin.placeId);
        if (home != null) {
            ServerWorld w = p.getServer().getOverworld();
            // Underground places (the Underground City) keep their own height; others stand on the surface.
            int y = home[1] < 40 ? home[1] : Math.max(home[1], w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, home[0], home[2]));
            p.teleport(w, home[0] + 0.5, y, home[2] + 0.5, p.getYaw(), 0);
            p.setSpawnPoint(w.getRegistryKey(), new BlockPos(home[0], y, home[2]), 0, true, false);
        }
        if (!pr.kitGiven) {
            Kit.give(p, pr, AotRpg.PLACES.get("cadet-training-camp"));
            AotRpg.SCHEDULER.later(40, () -> AotRpg.SEASON.daily(p));
            pr.kitGiven = true;
            AotRpg.PROFILES.save(p.getUuid());
        }
        AotRpg.sync(p, pr);
        AotRpg.NAMETAGS.update(p, pr);
        pr.questBase = 0;
        AotRpg.STORY.send(p, pr);
        intro(p, pr);
    }

    private void intro(ServerPlayerEntity p, Profile pr) {
        p.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 0.7f, 1f);
        Titles.show(p, Text.literal("YEAR 845").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("Humanity lives behind the walls").formatted(Formatting.GRAY), 20, 60, 20);
        AotRpg.SCHEDULER.later(100, () -> {
            if (p.isDisconnected()) return;
            Titles.show(p, Text.literal(pr.origin.title.toUpperCase()).formatted(Formatting.WHITE, Formatting.BOLD),
                Text.literal("Your home, for now").formatted(Formatting.GRAY), 20, 60, 20);
        });
        AotRpg.SCHEDULER.later(200, () -> {
            if (p.isDisconnected()) return;
            Titles.show(p, Text.literal(pr.name).formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(pr.discipline.title + " of the 104th Cadet Corps").formatted(Formatting.YELLOW), 20, 60, 20);
            p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.8f, 1f);
            boolean mod = AotRpg.hasClient(p);
            MutableText msg = Text.literal("\n\u2694 Chapter 1: The Recruit\n").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("Read your Recruitment Letter and report to the Cadet Training Camp"
                    + (mod ? " (gold marker on your minimap).\n" : ".\n")).formatted(Formatting.GRAY))
                .append(Text.literal(mod ? "Slay titans to gain XP. Press K for your character and skills.\n"
                    : "Slay titans to gain XP. Type /character to view your character.\n").formatted(Formatting.DARK_GRAY));
            p.sendMessage(msg);
        });
    }
}
