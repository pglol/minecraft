package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The furniture store. Pieces are bought from anywhere and shipped to the character's crate;
 * standing on your own property (inside your home or on your plot's land) you place them from
 * the crate, facing you, where you click. Breaking any block of a placed piece picks the whole
 * piece back up into the crate (it never drops as loose blocks).
 */
public final class Furniture {
    /**
     * A piece, drawn as if the player looks north: +x is to the right, -z is away from the
     * player, and fronts face south (towards the player). Parts: "dx,dy,dz block[state]".
     */
    public record Piece(String id, String title, String category, long price, String parts) {
        List<Part> list() {
            List<Part> out = new ArrayList<>();
            for (String p : parts.split(";")) {
                String[] a = p.trim().split(" ", 2);
                String[] o = a[0].split(",");
                out.add(new Part(new BlockPos(Integer.parseInt(o[0]), Integer.parseInt(o[1]), Integer.parseInt(o[2])), a[1]));
            }
            return out;
        }

        int[] size() {
            int x0 = 0, x1 = 0, y1 = 0, z0 = 0, z1 = 0;
            for (Part p : list()) {
                x0 = Math.min(x0, p.off.getX());
                x1 = Math.max(x1, p.off.getX());
                y1 = Math.max(y1, p.off.getY());
                z0 = Math.min(z0, p.off.getZ());
                z1 = Math.max(z1, p.off.getZ());
            }
            return new int[] {x0, x1, y1, z0, z1};
        }
    }

    record Part(BlockPos off, String state) { }

    public static final List<Piece> CATALOG = List.of(
        // Seating
        new Piece("chair", "Oak Chair", "Seating", 60, "0,0,0 oak_stairs[facing=north]"),
        new Piece("armchair", "Armchair", "Seating", 180,
            "0,0,0 spruce_stairs[facing=north];-1,0,0 spruce_trapdoor[facing=west,open=true];1,0,0 spruce_trapdoor[facing=east,open=true]"),
        new Piece("bench", "Long Bench", "Seating", 140, "-1,0,0 dark_oak_stairs[facing=north];0,0,0 dark_oak_stairs[facing=north];1,0,0 dark_oak_stairs[facing=north]"),
        new Piece("zabuton", "Hizuru Floor Cushions", "Seating", 90, "0,0,0 red_carpet;1,0,0 red_carpet"),
        // Tables
        new Piece("table", "Dining Table", "Tables", 160,
            "0,0,-1 spruce_fence;1,0,-1 spruce_fence;0,1,-1 spruce_pressure_plate;1,1,-1 spruce_pressure_plate;0,0,0 spruce_stairs[facing=south];1,0,0 spruce_stairs[facing=south]"),
        new Piece("desk", "Writing Desk", "Tables", 150, "0,0,-1 dark_oak_slab[type=top];1,0,-1 lectern[facing=south];0,0,0 oak_stairs[facing=south]"),
        new Piece("lowtable", "Hizuru Low Table", "Tables", 120, "0,0,-1 bamboo_mosaic_slab[type=bottom];1,0,-1 bamboo_mosaic_slab[type=bottom]"),
        // Beds
        new Piece("bed_red", "Red Bed", "Beds", 220, "0,0,0 red_bed[facing=north,part=foot];0,0,-1 red_bed[facing=north,part=head]"),
        new Piece("bed_blue", "Blue Bed", "Beds", 220, "0,0,0 blue_bed[facing=north,part=foot];0,0,-1 blue_bed[facing=north,part=head]"),
        new Piece("bed_white", "Linen Bed", "Beds", 220, "0,0,0 white_bed[facing=north,part=foot];0,0,-1 white_bed[facing=north,part=head]"),
        new Piece("futon", "Hizuru Futon", "Beds", 240,
            "0,0,0 white_bed[facing=north,part=foot];0,0,-1 white_bed[facing=north,part=head];1,0,0 gray_carpet;1,0,-1 gray_carpet"),
        // Storage
        new Piece("barrels", "Storage Barrels", "Storage", 200, "0,0,0 barrel[facing=up];1,0,0 barrel[facing=up];0,1,0 barrel[facing=up];1,1,0 barrel[facing=up]"),
        new Piece("bookshelf", "Bookshelf Wall", "Storage", 260,
            "-1,0,0 bookshelf;0,0,0 bookshelf;1,0,0 bookshelf;-1,1,0 bookshelf;0,1,0 chiseled_bookshelf[facing=south];1,1,0 bookshelf"),
        new Piece("weaponrack", "Weapon Rack", "Storage", 180, "0,0,0 spruce_planks;0,1,0 spruce_fence;1,0,0 spruce_planks;1,1,0 spruce_fence;0,2,0 spruce_slab[type=bottom];1,2,0 spruce_slab[type=bottom]"),
        // Kitchen and bath
        new Piece("kitchen", "Kitchen Counter", "Kitchen", 320,
            "-1,0,0 smoker[facing=south];0,0,0 water_cauldron[level=3];1,0,0 barrel[facing=south];-1,1,0 spruce_trapdoor[half=top,facing=south];1,1,0 spruce_trapdoor[half=top,facing=south]"),
        new Piece("stove", "Cast-iron Stove", "Kitchen", 240, "0,0,0 blast_furnace[facing=south];0,1,0 cobblestone_wall;0,2,0 cobblestone_wall"),
        new Piece("bath", "Copper Tub", "Kitchen", 280, "0,0,0 water_cauldron[level=3];1,0,0 water_cauldron[level=3]"),
        // Lighting and hearth
        new Piece("lamp", "Floor Lamp", "Lighting", 70, "0,0,0 spruce_fence;0,1,0 lantern"),
        new Piece("stonelantern", "Hizuru Stone Lantern", "Lighting", 110, "0,0,0 stone_brick_wall;0,1,0 lantern;0,2,0 stone_brick_slab[type=bottom]"),
        new Piece("candles", "Candle Stand", "Lighting", 60, "0,0,0 spruce_fence;0,1,0 white_candle[candles=3,lit=true]"),
        new Piece("fireplace", "Brick Fireplace", "Lighting", 420,
            "-1,0,0 bricks;0,0,0 campfire[facing=south];1,0,0 bricks;-1,1,0 bricks;0,1,0 bricks;1,1,0 bricks;-1,2,0 brick_slab[type=bottom];1,2,0 brick_slab[type=bottom];0,2,0 bricks"),
        // Decor
        new Piece("rug_red", "Red Rug", "Decor", 80, "-1,0,0 red_carpet;0,0,0 red_carpet;1,0,0 red_carpet;-1,0,-1 red_carpet;0,0,-1 red_carpet;1,0,-1 red_carpet"),
        new Piece("rug_green", "Survey Corps Rug", "Decor", 90,
            "-1,0,0 green_carpet;0,0,0 white_carpet;1,0,0 green_carpet;-1,0,-1 green_carpet;0,0,-1 white_carpet;1,0,-1 green_carpet"),
        new Piece("tatami", "Tatami Mats", "Decor", 150,
            "-1,0,0 green_carpet;0,0,0 lime_carpet;1,0,0 green_carpet;-1,0,-1 lime_carpet;0,0,-1 green_carpet;1,0,-1 lime_carpet"),
        new Piece("plant_fern", "Potted Fern", "Decor", 40, "0,0,0 potted_fern"),
        new Piece("plant_bamboo", "Potted Bamboo", "Decor", 45, "0,0,0 potted_bamboo"),
        new Piece("plant_azalea", "Flowering Azalea", "Decor", 55, "0,0,0 potted_flowering_azalea_bush"),
        new Piece("screen", "Paper Screen", "Decor", 130,
            "-1,0,0 bamboo_fence;0,0,0 white_stained_glass_pane[east=true,west=true];1,0,0 bamboo_fence;-1,1,0 bamboo_fence;0,1,0 white_stained_glass_pane[east=true,west=true];1,1,0 bamboo_fence"),
        new Piece("banner", "Wings of Freedom Banner", "Decor", 150, "0,0,0 green_banner[rotation=8]"),
        new Piece("clock", "Grandfather Clock", "Decor", 200, "0,0,0 dark_oak_log;0,1,0 dark_oak_trapdoor[facing=south,open=true];0,2,0 dark_oak_log;0,3,0 dark_oak_slab[type=bottom]"),
        new Piece("anvil", "Workshop Anvil (your forge)", "Storage", 2400, "0,0,0 anvil[facing=east];1,0,0 grindstone[face=floor,facing=south]"));

    public static Piece piece(String id) {
        for (Piece p : CATALOG) if (p.id().equals(id)) return p;
        return null;
    }

    /** A placed piece: its owner, where, and which way it faces. */
    static final class Placed {
        String owner;
        String id;
        String world;
        int x, y, z;
        int rot;
    }

    private static final class Data {
        Map<String, Map<String, Integer>> crates = new HashMap<>();
        List<Placed> placed = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();
    private Path file;
    private MinecraftServer server;
    /** World + block -> the piece that block belongs to. */
    private final Map<String, Placed> index = new HashMap<>();
    /** Players currently on their own property (for the hint and placing). */
    private final Map<java.util.UUID, String> onProperty = new HashMap<>();

    public void open(MinecraftServer server) {
        this.server = server;
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("furniture.json");
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read furniture.json", e);
        }
        if (data == null) data = new Data();
        if (data.crates == null) data.crates = new HashMap<>();
        if (data.placed == null) data.placed = new ArrayList<>();
        index.clear();
        for (Placed pl : data.placed) indexPiece(pl, true);
        onProperty.clear();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save furniture.json", e);
        }
    }

    private static String key(String world, BlockPos pos) {
        return world + "|" + pos.asLong();
    }

    private static BlockRotation rotation(Direction facing) {
        return switch (facing) {
            case EAST -> BlockRotation.CLOCKWISE_90;
            case SOUTH -> BlockRotation.CLOCKWISE_180;
            case WEST -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
    }

    private void indexPiece(Placed pl, boolean add) {
        Piece p = piece(pl.id);
        if (p == null) return;
        BlockRotation rot = BlockRotation.values()[pl.rot];
        BlockPos o = new BlockPos(pl.x, pl.y, pl.z);
        for (Part part : p.list()) {
            String k = key(pl.world, o.add(part.off().rotate(rot)));
            if (add) index.put(k, pl);
            else index.remove(k);
        }
    }

    private static BlockState parse(String s) {
        try {
            return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), s.contains(":") ? s : "minecraft:" + s, false).blockState();
        } catch (Exception e) {
            AotRpg.LOG.warn("Bad furniture block {}", s);
            return Blocks.OAK_PLANKS.getDefaultState();
        }
    }

    private Map<String, Integer> crate(ServerPlayerEntity p) {
        return data.crates.computeIfAbsent(AotRpg.HOMES.stem(p), k -> new LinkedHashMap<>());
    }

    /** Is this block on the player's own property (their home instance, or their plot's land)? */
    public boolean ownProperty(ServerPlayerEntity p, BlockPos pos) {
        if (p.getWorld().getRegistryKey() == Homes.WORLD) return AotRpg.HOMES.canBuild(p, pos);
        return p.getWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD && HomePlots.ownsAt(p, pos);
    }

    // ------------------------------------------------------------------ actions

    public void action(ServerPlayerEntity p, String action, String id, BlockPos at) {
        if (!AotRpg.PROFILES.get(p.getUuid()).created) return;
        switch (action) {
            case "buy" -> buy(p, id);
            case "place" -> place(p, id, at);
            default -> { }
        }
        send(p, action.equals("open"));
    }

    private void buy(ServerPlayerEntity p, String id) {
        Piece pc = piece(id);
        if (pc == null) return;
        if (!AotRpg.WALLET.spendMarks(p, pc.price())) {
            p.sendMessage(Text.literal("You need " + pc.price() + " Marks.").formatted(Formatting.RED), true);
            return;
        }
        crate(p).merge(id, 1, Integer::sum);
        save();
        p.sendMessage(Text.literal(pc.title() + " shipped to your home.").formatted(Formatting.GOLD)
            .append(Text.literal("  Place it from the property menu when you're there.").formatted(Formatting.GRAY)), true);
        p.playSoundToPlayer(SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.MASTER, 0.5f, 1.1f);
    }

    private void place(ServerPlayerEntity p, String id, BlockPos at) {
        Piece pc = piece(id);
        Map<String, Integer> crate = crate(p);
        if (pc == null || at == null || crate.getOrDefault(id, 0) <= 0) return;
        ServerWorld w = p.getServerWorld();
        if (p.squaredDistanceTo(at.toCenterPos()) > 8 * 8) return;
        BlockRotation rot = rotation(p.getHorizontalFacing());
        List<Part> parts = pc.list();
        // Every block must be free, and on the player's own property.
        for (Part part : parts) {
            BlockPos pos = at.add(part.off().rotate(rot));
            if (!ownProperty(p, pos)) {
                p.sendMessage(Text.literal("Furniture goes on your own property.").formatted(Formatting.RED), true);
                return;
            }
            BlockState cur = w.getBlockState(pos);
            if (!cur.isAir() && !cur.isReplaceable()) {
                p.sendMessage(Text.literal("Not enough room there.").formatted(Formatting.RED), true);
                return;
            }
        }
        WorldCare.quiet(true);
        try {
            for (Part part : parts) {
                BlockPos pos = at.add(part.off().rotate(rot));
                w.setBlockState(pos, parse(part.state()).rotate(rot), Block.NOTIFY_ALL);
            }
        } finally {
            WorldCare.quiet(false);
        }
        Placed pl = new Placed();
        pl.owner = AotRpg.HOMES.stem(p);
        pl.id = id;
        pl.world = w.getRegistryKey().getValue().toString();
        pl.x = at.getX();
        pl.y = at.getY();
        pl.z = at.getZ();
        pl.rot = rot.ordinal();
        data.placed.add(pl);
        AotRpg.TASKS.count(p, Tasks.FURNITURE, 1);
        indexPiece(pl, true);
        if (crate.merge(id, -1, Integer::sum) <= 0) crate.remove(id);
        save();
        w.playSound(null, at, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1f, 0.9f);
        p.sendMessage(Text.literal("Placed " + pc.title() + "  ·  break it to pick it back up").formatted(Formatting.GOLD), true);
    }

    /**
     * Breaking a block of a placed piece: the owner picks the whole piece up into their crate
     * (nobody else can break it). Returns true when it was furniture (the break is cancelled).
     */
    public boolean onBreak(ServerPlayerEntity p, BlockPos pos) {
        Placed pl = index.get(key(p.getWorld().getRegistryKey().getValue().toString(), pos));
        if (pl == null) return false;
        if (!pl.owner.equals(AotRpg.HOMES.stem(p))) {
            p.sendMessage(Text.literal("That isn't yours.").formatted(Formatting.GRAY), true);
            return true;
        }
        Piece pc = piece(pl.id);
        ServerWorld w = p.getServerWorld();
        BlockRotation rot = BlockRotation.values()[pl.rot];
        BlockPos o = new BlockPos(pl.x, pl.y, pl.z);
        for (Part part : pc.list()) {
            BlockEntity be = w.getBlockEntity(o.add(part.off().rotate(rot)));
            if (be instanceof Inventory inv && !inv.isEmpty()) {
                p.sendMessage(Text.literal("Empty it before you move it.").formatted(Formatting.RED), true);
                return true;
            }
        }
        indexPiece(pl, false);
        WorldCare.quiet(true);
        try {
            // Top down, so beds and plants come off cleanly without drops.
            List<Part> parts = new ArrayList<>(pc.list());
            parts.sort((x, y) -> Integer.compare(y.off().getY(), x.off().getY()));
            for (Part part : parts) {
                w.setBlockState(o.add(part.off().rotate(rot)), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
            }
        } finally {
            WorldCare.quiet(false);
        }
        data.placed.remove(pl);
        crate(p).merge(pl.id, 1, Integer::sum);
        save();
        w.playSound(null, pos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1f, 1.1f);
        p.sendMessage(Text.literal(pc.title() + " back in your crate").formatted(Formatting.GOLD), true);
        send(p, false);
        return true;
    }

    // ------------------------------------------------------------------ property hint

    /** Every half second: tells the client when the player steps onto or off their property. */
    public void tick(ServerPlayerEntity p, int ticks) {
        if (ticks % 10 != 0) return;
        String where = "";
        if (p.getWorld().getRegistryKey() == Homes.WORLD) {
            if (AotRpg.HOMES.canBuild(p, p.getBlockPos())) where = "Your home";
        } else if (HomePlots.ownsAt(p, p.getBlockPos())) where = "Your property";
        String before = onProperty.getOrDefault(p.getUuid(), "");
        if (before.equals(where)) return;
        onProperty.put(p.getUuid(), where);
        if (ServerPlayNetworking.canSend(p, Net.PropertyState.ID)) ServerPlayNetworking.send(p, new Net.PropertyState(where));
    }

    public void forget(java.util.UUID id) {
        onProperty.remove(id);
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.FurnitureView.ID)) return;
        List<Net.FurniturePiece> list = new ArrayList<>();
        Map<String, Integer> crate = crate(p);
        for (Piece pc : CATALOG) {
            int[] s = pc.size();
            list.add(new Net.FurniturePiece(pc.id(), pc.title(), pc.category(), pc.price(), crate.getOrDefault(pc.id(), 0),
                iconOf(pc), s[0], s[1], s[2], s[3], s[4]));
        }
        ServerPlayNetworking.send(p, new Net.FurnitureView(list, ownProperty(p, p.getBlockPos()), open));
    }

    /** The piece's most telling block, as an item id for its icon. */
    private static String iconOf(Piece pc) {
        String best = pc.list().get(0).state();
        for (Part part : pc.list()) {
            String s = part.state();
            if (s.contains("bed") || s.contains("lantern") || s.contains("smoker") || s.contains("campfire") || s.contains("barrel")
                || s.contains("potted") || s.contains("banner") || s.contains("lectern") || s.contains("anvil") || s.contains("cauldron")) {
                best = s;
                break;
            }
        }
        String name = best.replaceAll("\\[.*", "");
        if (name.startsWith("potted_")) name = name.substring(7);
        if (name.equals("water_cauldron")) name = "cauldron";
        if (name.equals("potted_flowering_azalea_bush") || name.equals("flowering_azalea_bush")) name = "flowering_azalea";
        return "minecraft:" + name;
    }
}
