package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;

/** What the server last told us about our character. */
public final class ClientState {
    private ClientState() {}

    public static Net.Sync profile;
    public static float stamina = -1, maxStamina = 100;
    public static boolean exhausted;
    /** The other members of our party (empty = no party). */
    public static java.util.List<Net.PartyMember> party = java.util.List.of();

    /** Character names of online players, for name plates. */
    public static java.util.Map<java.util.UUID, Net.RosterEntry> roster = new java.util.HashMap<>();
    /** Current story objective, or null. */
    public static Net.Objective objective;
    public static boolean minimap = true;
    /** Cooking fire positions as x,y,z triples. */
    public static int[] campfires = new int[0];
    public static Net.DeathInfo death;
    public static java.util.List<Net.Area> areas = java.util.List.of();
    public static java.util.List<Net.Marker> markers = java.util.List.of();
    public static java.util.List<Net.QuestView> quests = java.util.List.of();

    public static java.util.List<String> cosmetics = java.util.List.of("trail_tracer");
    public static String trail = "trail_tracer";
    /** Your cosmetics by slot. */
    public static java.util.Map<String, String> worn = new java.util.HashMap<>();
    public static boolean cosmeticsAll;
    public static long marks, gold;
    public static Net.CharacterList characters;
    public static Net.MarketView market;
    public static java.util.List<Net.ExchangeEntry> exchange = java.util.List.of();
    public static Net.FactionView factions;
    public static Net.StatsView stats;
    public static Net.RegimentView regiments;
    /** The satchel's contents (slot -> stack) and its size. */
    public static java.util.Map<Integer, net.minecraft.item.ItemStack> bag = new java.util.TreeMap<>();
    public static int bagSize = 240;

    /** A stack by address: 0-35 the player's inventory, 1000 + i the satchel. */
    public static net.minecraft.item.ItemStack stackAt(int addr) {
        if (addr >= com.pglol.aotrpg.Satchel.BAG) return bag.getOrDefault(addr - com.pglol.aotrpg.Satchel.BAG, net.minecraft.item.ItemStack.EMPTY);
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        return mc.player == null || addr < 0 || addr >= mc.player.getInventory().main.size() ? net.minecraft.item.ItemStack.EMPTY
            : mc.player.getInventory().main.get(addr);
    }
    public static Net.HomeView home;
    public static Net.HomeAdminView homeAdmin;
    public static Net.ForgeView forge;
    public static Net.ModeView modes;
    public static Net.PassView pass;
    public static Net.StableView stable;

    /** Whether we know you own a horse (from the last stable view). */
    public static boolean hasHorse() {
        return stable != null && !stable.horses().isEmpty();
    }
    public static Net.TasksView tasks;
    /** "Your home" / "Your property" while standing on it, else "". */
    public static String property = "";
    public static Net.FurnitureView furniture;
    /** The furniture piece being placed, or null. */
    public static String placing;
    public static Net.EventView event;
    public static Net.SocialView social;
    public static java.util.Map<java.util.UUID, Net.SheathState> sheaths = new java.util.HashMap<>();

    public static Net.PartyMember partyMember(java.util.UUID id) {
        for (Net.PartyMember m : party) if (m.id().equals(id)) return m;
        return null;
    }

    /** Forget the character only (a reset or a new character); world data stays. */
    public static void resetCharacter() {
        profile = null;
        stamina = -1;
        maxStamina = 100;
        exhausted = false;
        objective = null;
        death = null;
    }

    public static void reset() {
        profile = null;
        property = "";
        placing = null;
        worn.clear();
        CosmeticFx.clear();
        stamina = -1;
        maxStamina = 100;
        exhausted = false;
        party = java.util.List.of();
        roster = new java.util.HashMap<>();
        objective = null;
        campfires = new int[0];
        death = null;
        areas = java.util.List.of();
        markers = java.util.List.of();
        quests = java.util.List.of();
    }
}
