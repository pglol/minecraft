package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtString;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Crafting, our way: no crafting grid anywhere. You buy a portable workbench from a town's
 * Craftsman (a Builder's Bench, a Smith's Forge, a Quartermaster's Desk, a Field Kitchen), carry
 * it out, and set it down wherever the work is: in a clearing you've just felled, at a cave mouth,
 * behind a wall on a run. It stands for five minutes, then packs itself up and goes back in your
 * satchel. What you can make at it depends on what you know (see Recipes).
 */
public final class Crafting {
    private Crafting() {}

    /** Lifestyle skills the benches (and harvesting) train. */
    public static final String CARPENTRY = "carpentry", ENGINEERING = "engineering", WOODCUTTING = "woodcutting", MINING = "mining";

    private static final String BENCH_KEY = "aot_bench";
    public static final long DEPLOY_MS = 5 * 60 * 1000L;

    private record Deployed(UUID owner, Recipes.Bench bench, RegistryKey<World> world, BlockPos pos, long until) { }

    private static final Map<String, Deployed> deployed = new HashMap<>();
    /** Benches owed back to players who were away when theirs packed up. */
    private static final Map<UUID, List<String>> owed = new HashMap<>();
    /** Which bench each player is working at. */
    private static final Map<UUID, String> using = new HashMap<>();
    private static final Map<UUID, Long> lastCraft = new HashMap<>();
    private static final Set<String> warned = new HashSet<>();
    private static Path file;
    private static boolean stopping;

    private static String key(RegistryKey<World> w, BlockPos pos) {
        return w.getValue() + "|" + pos.asLong();
    }

    // ------------------------------------------------------------------ the bench as an item

    public static ItemStack benchItem(Recipes.Bench b) {
        ItemStack s = new ItemStack(b.look);
        NbtCompound tag = new NbtCompound();
        tag.putString(BENCH_KEY, b.name());
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(b.title).styled(x -> x.withItalic(false).withColor(b.color)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("Portable workbench").styled(x -> x.withItalic(false).withColor(0xE8DCC0)),
            Text.literal("Set it down: it stands 5 minutes").styled(x -> x.withItalic(false).withColor(0xE8DCC0)))));
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        return s;
    }

    public static Recipes.Bench benchOf(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return null;
        String n = c.copyNbt().getString(BENCH_KEY);
        if (n.isEmpty()) return null;
        try {
            return Recipes.Bench.valueOf(n);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ hooks

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp) || hand != Hand.MAIN_HAND) return ActionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            // Working at a bench that's standing.
            String k = key(world.getRegistryKey(), pos);
            if (deployed.containsKey(k)) {
                open(sp, k);
                return ActionResult.SUCCESS;
            }
            // Setting one down.
            Recipes.Bench b = benchOf(sp.getMainHandStack());
            if (b != null) {
                place(sp, hit, b);
                return ActionResult.SUCCESS;
            }
            // No crafting grids: a plain crafting table is just furniture.
            if (world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE) && !(sp.isCreative() && sp.hasPermissionLevel(2))) {
                Notify.toast(sp, Text.literal("Crafting happens at a workbench").formatted(Formatting.GOLD),
                    Text.literal("Buy one from a town's Craftsman"), 0xC89A5A, "minecraft:crafting_table", "craft_hint");
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(stack);
            String id = Recipes.schematicOf(stack);
            if (id != null) {
                learn(sp, stack, id);
                return TypedActionResult.success(stack);
            }
            if (benchOf(stack) != null) {
                Notify.toast(sp, Text.literal("Set it down on the ground").formatted(Formatting.GOLD), null, 0xC89A5A, null, "craft_hint");
                return TypedActionResult.fail(stack);
            }
            return TypedActionResult.pass(stack);
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            String k = key(world.getRegistryKey(), pos);
            Deployed d = deployed.get(k);
            if (d == null) return true;
            // Only its owner can pack a bench up early.
            if (player instanceof ServerPlayerEntity sp && (d.owner().equals(sp.getUuid()) || sp.hasPermissionLevel(2)) && world.getServer() != null) {
                pack(world.getServer(), k, "Packed up");
            }
            return false;
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.CraftAction.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            if (payload.action().equals("close")) {
                using.remove(p.getUuid());
                return;
            }
            if (payload.action().equals("craft")) craft(p, payload.recipe(), payload.qty());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> payOwed(handler.player));
        ServerLifecycleEvents.SERVER_STARTED.register(Crafting::load);
        // Nothing left standing across a restart: every bench goes home first.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            stopping = true;
            for (String k : new ArrayList<>(deployed.keySet())) pack(server, k, null);
            save();
        });
    }

    // ------------------------------------------------------------------ setting down, packing up

    private static void place(ServerPlayerEntity p, BlockHitResult hit, Recipes.Bench b) {
        ServerWorld w = p.getServerWorld();
        RegistryKey<World> wk = w.getRegistryKey();
        BlockPos at = w.getBlockState(hit.getBlockPos()).isReplaceable() ? hit.getBlockPos() : hit.getBlockPos().offset(hit.getSide());
        boolean allowed = wk != Extraction.SKY
            && (wk != Homes.WORLD || AotRpg.CARE.canBuild(p, at))
            && (HomePlots.plotAt(at, 0) < 0 || AotRpg.HOMES.ownsPlotAt(p, at));
        BlockState here = w.getBlockState(at);
        boolean room = here.isReplaceable() && here.getFluidState().isEmpty() && w.getBlockState(at.up()).isReplaceable()
            && w.getBlockState(at.down()).isSideSolidFullSquare(w, at.down(), Direction.UP);
        if (!allowed || !room) {
            Notify.toast(p, Text.literal(!allowed ? "Not here" : "No room to set it down").formatted(Formatting.RED), null, 0xC0463A, null, "craft_hint");
            return;
        }
        // One bench out at a time: the last one packs up.
        for (var e : new ArrayList<>(deployed.entrySet())) if (e.getValue().owner().equals(p.getUuid())) pack(p.getServer(), e.getKey(), null);
        ItemStack hand = p.getMainHandStack();
        if (benchOf(hand) != b) return;
        hand.decrement(1);
        BlockState st = ((BlockItem) b.look).getBlock().getDefaultState();
        if (st.contains(Properties.HORIZONTAL_FACING)) st = st.with(Properties.HORIZONTAL_FACING, p.getHorizontalFacing().getOpposite());
        WorldCare.quiet(true);
        try {
            w.setBlockState(at, st);
        } finally {
            WorldCare.quiet(false);
        }
        String k = key(wk, at);
        deployed.put(k, new Deployed(p.getUuid(), b, wk, at.toImmutable(), System.currentTimeMillis() + DEPLOY_MS));
        w.playSound(null, at, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1f, 0.8f);
        w.playSound(null, at, SoundEvents.ITEM_ARMOR_EQUIP_CHAIN.value(), SoundCategory.BLOCKS, 0.7f, 0.9f);
        w.spawnParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, 12, 0.4, 0.05, 0.4, 0.02);
        save();
        open(p, k);
    }

    /** Takes a bench down (why: a toast for its owner; null for none) and sends it back to them. */
    private static void pack(MinecraftServer server, String k, String why) {
        Deployed d = deployed.remove(k);
        if (d == null) return;
        warned.remove(k);
        ServerWorld w = server.getWorld(d.world());
        if (w != null) {
            w.getChunk(d.pos());
            if (w.getBlockState(d.pos()).isOf(((BlockItem) d.bench().look).getBlock())) {
                WorldCare.quiet(true);
                try {
                    w.setBlockState(d.pos(), Blocks.AIR.getDefaultState());
                } finally {
                    WorldCare.quiet(false);
                }
                w.spawnParticles(ParticleTypes.POOF, d.pos().getX() + 0.5, d.pos().getY() + 0.5, d.pos().getZ() + 0.5, 14, 0.3, 0.3, 0.3, 0.02);
                w.playSound(null, d.pos(), SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 1f, 0.7f);
            }
        }
        for (var e : new ArrayList<>(using.entrySet())) {
            if (!e.getValue().equals(k)) continue;
            using.remove(e.getKey());
            ServerPlayerEntity v = server.getPlayerManager().getPlayer(e.getKey());
            if (v != null && ServerPlayNetworking.canSend(v, Net.CraftView.ID)) {
                ServerPlayNetworking.send(v, new Net.CraftView("", "", 0, -1, "", 0, List.of(), false));
            }
        }
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(d.owner());
        if (owner != null && !stopping) {
            Loot.claim(owner, benchItem(d.bench()));
            if (why != null) Notify.toast(owner, Text.literal(d.bench().title + ": " + why).formatted(Formatting.GOLD), null, d.bench().color,
                net.minecraft.registry.Registries.ITEM.getId(d.bench().look).toString(), "bench");
        } else {
            owed.computeIfAbsent(d.owner(), x -> new ArrayList<>()).add(d.bench().name());
        }
        save();
    }

    private static void payOwed(ServerPlayerEntity p) {
        List<String> list = owed.remove(p.getUuid());
        if (list == null) return;
        for (String n : list) {
            try {
                Loot.claim(p, benchItem(Recipes.Bench.valueOf(n)));
            } catch (Exception ignored) {
            }
        }
        save();
    }

    public static void tick(MinecraftServer server, int ticks) {
        if (ticks % 20 != 11 || deployed.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var e : new ArrayList<>(deployed.entrySet())) {
            Deployed d = e.getValue();
            ServerWorld w = server.getWorld(d.world());
            long left = d.until() - now;
            // Gone some other way (a titan stepped on it): it still comes home.
            boolean broken = w != null && w.isChunkLoaded(d.pos().getX() >> 4, d.pos().getZ() >> 4)
                && !w.getBlockState(d.pos()).isOf(((BlockItem) d.bench().look).getBlock());
            if (left <= 0 || broken || w == null) {
                pack(server, e.getKey(), left <= 0 ? "Time's up, packed up" : "Knocked down, packed up");
                continue;
            }
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(d.owner());
            if (left < 30_000 && warned.add(e.getKey()) && owner != null) {
                Notify.toast(owner, Text.literal(d.bench().title + " packs up in 30s").formatted(Formatting.GOLD), null, d.bench().color, null, "bench");
                owner.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.5f, 1.4f);
            }
            // A working bench looks worked: sparks at the forge, steam from the kitchen.
            if (w.isChunkLoaded(d.pos().getX() >> 4, d.pos().getZ() >> 4) && ticks % 40 == 11) {
                var c = d.pos().toCenterPos();
                switch (d.bench()) {
                    case SMITH -> w.spawnParticles(ParticleTypes.LAVA, c.x, c.y + 0.5, c.z, 1, 0.2, 0, 0.2, 0);
                    case KITCHEN -> w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y + 0.6, c.z, 1, 0.05, 0, 0.05, 0.01);
                    default -> w.spawnParticles(ParticleTypes.WAX_ON, c.x, c.y + 0.6, c.z, 2, 0.3, 0.1, 0.3, 0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ working at one

    private static void open(ServerPlayerEntity p, String k) {
        Deployed d = deployed.get(k);
        if (d == null || !ServerPlayNetworking.canSend(p, Net.CraftView.ID)) return;
        using.put(p.getUuid(), k);
        p.playSoundToPlayer(sound(d.bench()), SoundCategory.BLOCKS, 0.6f, 1.1f);
        send(p, d, true);
    }

    private static SoundEvent sound(Recipes.Bench b) {
        return switch (b) {
            case SMITH -> SoundEvents.BLOCK_ANVIL_USE;
            case KITCHEN -> SoundEvents.BLOCK_CAMPFIRE_CRACKLE;
            case QUARTERMASTER -> SoundEvents.ITEM_BOOK_PAGE_TURN;
            default -> SoundEvents.UI_STONECUTTER_TAKE_RESULT;
        };
    }

    /** Everything you're carrying that could go into a recipe (inventory and satchel). */
    private static List<ItemStack> carried(ServerPlayerEntity p) {
        List<ItemStack> out = new ArrayList<>();
        for (int a : AotRpg.SATCHEL.addresses(p)) out.add(AotRpg.SATCHEL.at(p, a));
        return out;
    }

    private static int have(List<ItemStack> carried, Recipes.Ing ing) {
        int n = 0;
        for (ItemStack s : carried) if (ing.test().test(s)) n += s.getCount();
        return n;
    }

    private static void send(ServerPlayerEntity p, Deployed d, boolean openIt) {
        List<ItemStack> carried = carried(p);
        List<Net.CraftRow> rows = new ArrayList<>();
        for (Recipes.Recipe r : Recipes.of(d.bench())) {
            List<ItemStack> ins = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            List<Integer> have = new ArrayList<>();
            for (Recipes.Ing i : r.inputs()) {
                ins.add(i.icon());
                labels.add(i.label());
                have.add(have(carried, i));
            }
            boolean known = Recipes.known(p, r);
            rows.add(new Net.CraftRow(r.id(), r.name(), r.category(), r.preview(), ins, labels, have, known, known ? "" : Recipes.hint(r)));
        }
        int secs = (int) Math.max(0, (d.until() - System.currentTimeMillis()) / 1000);
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        String skill = d.bench().skill;
        ServerPlayNetworking.send(p, new Net.CraftView(d.bench().name(), d.bench().title, d.bench().color, secs,
            Character.toUpperCase(skill.charAt(0)) + skill.substring(1), Lifestyle.level(pr, skill), rows, openIt));
    }

    private static void craft(ServerPlayerEntity p, String id, int qty) {
        String k = using.get(p.getUuid());
        Deployed d = k == null ? null : deployed.get(k);
        Recipes.Recipe r = Recipes.get(id);
        if (d == null || r == null || r.bench() != d.bench() || p.getServerWorld().getRegistryKey() != d.world()) return;
        if (p.squaredDistanceTo(d.pos().toCenterPos()) > 7 * 7) {
            Notify.toast(p, Text.literal("Too far from the bench").formatted(Formatting.RED), null, 0xC0463A, null, "craft_hint");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCraft.getOrDefault(p.getUuid(), 0L) < 150) return;
        lastCraft.put(p.getUuid(), now);
        if (!Recipes.known(p, r)) return;
        List<ItemStack> carried = carried(p);
        int n = Math.max(1, Math.min(64, qty));
        for (Recipes.Ing i : r.inputs()) n = Math.min(n, have(carried, i) / i.count());
        if (n <= 0) {
            Notify.toast(p, Text.literal("Missing materials").formatted(Formatting.RED), null, 0xC0463A, null, "craft_hint");
            p.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.6f, 0.6f);
            return;
        }
        for (Recipes.Ing i : r.inputs()) consume(p, i, i.count() * n);
        // What comes out: stackable things in full stacks, one-offs (rolled gear) one at a time.
        ItemStack sample = r.make().apply(p);
        if (sample.isStackable()) {
            int total = sample.getCount() * n;
            while (total > 0) {
                ItemStack s = sample.copy();
                s.setCount(Math.min(total, s.getMaxCount()));
                total -= s.getCount();
                Loot.claim(p, s);
            }
        } else {
            Loot.claim(p, sample);
            for (int i = 1; i < n; i++) Loot.claim(p, r.make().apply(p));
        }
        Lifestyle.add(p, d.bench().skill, (long) r.xp() * n);
        ServerWorld w = p.getServerWorld();
        var c = d.pos().toCenterPos();
        w.playSound(null, d.pos(), sound(d.bench()), SoundCategory.BLOCKS, 0.8f, 0.9f + w.random.nextFloat() * 0.2f);
        w.spawnParticles(d.bench() == Recipes.Bench.SMITH ? ParticleTypes.FLAME : d.bench() == Recipes.Bench.KITCHEN ? ParticleTypes.SMOKE : ParticleTypes.CRIT,
            c.x, c.y + 0.6, c.z, 10, 0.25, 0.1, 0.25, 0.03);
        send(p, d, false);
    }

    /** Takes this many matching items, from the satchel first, then your pockets. */
    private static void consume(ServerPlayerEntity p, Recipes.Ing ing, int amount) {
        List<Integer> addrs = new ArrayList<>(AotRpg.SATCHEL.addresses(p));
        // Satchel addresses come after the inventory ones: go through them first.
        java.util.Collections.reverse(addrs);
        for (int a : addrs) {
            if (amount <= 0) break;
            ItemStack s = AotRpg.SATCHEL.at(p, a);
            if (!ing.test().test(s)) continue;
            int k = Math.min(amount, s.getCount());
            ItemStack rest = s.copy();
            rest.decrement(k);
            AotRpg.SATCHEL.set(p, a, rest.isEmpty() ? ItemStack.EMPTY : rest);
            amount -= k;
        }
        AotRpg.SATCHEL.save(p.getUuid());
    }

    // ------------------------------------------------------------------ schematics

    private static void learn(ServerPlayerEntity p, ItemStack stack, String id) {
        Recipes.Recipe r = Recipes.get(id);
        if (r == null) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.recipes.add(id)) {
            Notify.toast(p, Text.literal("You already know this").formatted(Formatting.GRAY), Text.literal(r.name()), 0x9A9486, null, "craft_hint");
            return;
        }
        stack.decrement(1);
        AotRpg.PROFILES.save(p.getUuid());
        int tier = r.tier();
        int col = tier >= 3 ? 0xC055FF : tier == 2 ? 0x5599FF : 0x55FF55;
        Notify.toast(p, Text.literal("RECIPE LEARNED").styled(x -> x.withBold(true).withColor(col)), Text.literal(r.name() + "  ·  " + r.bench().title),
            col, net.minecraft.registry.Registries.ITEM.getId(r.preview().getItem()).toString(), null);
        p.playSoundToPlayer(SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1f, 0.9f);
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.45f, 1.5f);
        p.getServerWorld().spawnParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.2, p.getZ(), 30, 0.4, 0.5, 0.4, 0.6);
        // Open at a bench: the new line shows up straight away.
        String k = using.get(p.getUuid());
        if (k != null && deployed.containsKey(k)) send(p, deployed.get(k), false);
    }

    // ------------------------------------------------------------------ saving

    private static void load(MinecraftServer server) {
        stopping = false;
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("benches.dat");
        deployed.clear();
        owed.clear();
        if (!Files.exists(file)) return;
        try {
            NbtCompound tag = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            // Anything still standing from before a crash packs up as soon as its chunk is about.
            for (NbtElement el : tag.getList("deployed", NbtElement.COMPOUND_TYPE)) {
                NbtCompound c = (NbtCompound) el;
                RegistryKey<World> wk = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(c.getString("world")));
                BlockPos pos = BlockPos.fromLong(c.getLong("pos"));
                deployed.put(key(wk, pos), new Deployed(c.getUuid("owner"), Recipes.Bench.valueOf(c.getString("bench")), wk, pos, 0));
            }
            NbtCompound o = tag.getCompound("owed");
            for (String u : o.getKeys()) {
                List<String> l = new ArrayList<>();
                for (NbtElement el : o.getList(u, NbtElement.STRING_TYPE)) l.add(el.asString());
                owed.put(UUID.fromString(u), l);
            }
        } catch (Exception e) {
            AotRpg.LOG.warn("Could not read benches: {}", e.toString());
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            NbtCompound tag = new NbtCompound();
            NbtList list = new NbtList();
            for (Deployed d : deployed.values()) {
                NbtCompound c = new NbtCompound();
                c.putUuid("owner", d.owner());
                c.putString("bench", d.bench().name());
                c.putString("world", d.world().getValue().toString());
                c.putLong("pos", d.pos().asLong());
                list.add(c);
            }
            tag.put("deployed", list);
            NbtCompound o = new NbtCompound();
            for (var e : owed.entrySet()) {
                NbtList l = new NbtList();
                for (String s : e.getValue()) l.add(NbtString.of(s));
                o.put(e.getKey().toString(), l);
            }
            tag.put("owed", o);
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, file);
        } catch (Exception e) {
            AotRpg.LOG.warn("Could not save benches: {}", e.toString());
        }
    }
}
