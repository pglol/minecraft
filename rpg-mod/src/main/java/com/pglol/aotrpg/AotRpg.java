package com.pglol.aotrpg;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.util.Hand;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.util.ActionResult;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Attack on Titan RPG: character creation, levels, stats, skills and stamina. */
public final class AotRpg implements ModInitializer {
    public static final String MOD_ID = "aot_rpg";
    public static final Logger LOG = LoggerFactory.getLogger("AoT RPG");

    public static final ProfileStore PROFILES = new ProfileStore();
    public static final Places PLACES = new Places();
    public static final Scheduler SCHEDULER = new Scheduler();
    public static final Progression PROGRESSION = new Progression();
    public static final CharacterCreation CREATION = new CharacterCreation();
    public static final Nametags NAMETAGS = new Nametags();
    public static final Stamina STAMINA = new Stamina();
    public static final Parties PARTIES = new Parties();
    public static final Story STORY = new Story();
    public static final Satchel SATCHEL = new Satchel();
    public static final Cooking COOKING = new Cooking();
    public static final DeathCare DEATH = new DeathCare();
    public static final Quests QUESTS = new Quests();
    public static final TitanGuard GUARD = new TitanGuard();
    public static final QuickHeal HEAL = new QuickHeal();
    public static final Cosmetics COSMETICS = new Cosmetics();
    public static final WorldCare CARE = new WorldCare();
    public static final Loadout LOADOUT = new Loadout();
    public static final Characters CHARACTERS = new Characters();
    public static final Wallet WALLET = new Wallet();
    public static final Gear GEAR = new Gear();
    public static final Combat COMBAT = new Combat();
    public static final Market MARKET = new Market();
    public static final Roles ROLES = new Roles();
    public static final Homes HOMES = new Homes();
    public static final Forge FORGE = new Forge();
    public static final GameModes MODES = new GameModes();
    public static final Exchange EXCHANGE = new Exchange();
    public static final Factions FACTIONS = new Factions();
    public static final Waves WAVES = new Waves();
    public static final Grab GRAB = new Grab();
    private static final java.util.Map<java.util.UUID, Long> LAST_SHOT = new java.util.HashMap<>();

    /** True if this player runs the mod on their client (custom screens and HUD). */
    public static boolean hasClient(ServerPlayerEntity p) {
        return ServerPlayNetworking.canSend(p, Net.Sync.ID);
    }

    /** Map image info, areas and campfires: everything the map and minimap need. */
    public static void sendWorldData(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.Campfires.ID)) return;
        ServerPlayNetworking.send(p, new Net.Campfires(PLACES.campfireArray()));
        ServerPlayNetworking.send(p, new Net.Areas(PLACES.areas()));
        Net.MapFiles mi = PLACES.mapInfo();
        if (mi != null) ServerPlayNetworking.send(p, mi);
    }

    /** Sends the character to the player's HUD, or updates the boss bar for vanilla clients. */
    public static void sync(ServerPlayerEntity p, Profile pr) {
        if (!pr.created) return;
        if (hasClient(p)) ServerPlayNetworking.send(p, Net.Sync.of(pr));
        PROGRESSION.updateBar(p, pr);
    }

    private int ticks;

    @Override
    public void onInitialize() {
        Net.register();
        SatchelHandler.register();
        ServerPlayNetworking.registerGlobalReceiver(Net.MarketAction.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            if (!PROFILES.get(p.getUuid()).created) return;
            switch (payload.action()) {
                case "open" -> MARKET.open(p);
                case "buy" -> MARKET.buy(p, payload.item(), payload.qty());
                case "sell" -> MARKET.sell(p, payload.item(), payload.qty());
                case "sellgear" -> MARKET.sellGear(p, payload.qty());
                case "exchange" -> EXCHANGE.send(p);
                case "list" -> EXCHANGE.list(p, payload.qty(), payload.number());
                case "buylisting" -> EXCHANGE.buy(p, payload.number());
                case "cancel" -> EXCHANGE.cancel(p, payload.number());
                default -> { }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.ModeAction.ID, (payload, ctx) -> {
            if (payload.mode().equals("open")) MODES.send(ctx.player(), true);
            else MODES.choose(ctx.player(), payload.mode());
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.ForgeAction.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            // Only at your own home forge.
            if (p.getWorld().getRegistryKey() != Homes.WORLD || !HOMES.canBuild(p, p.getBlockPos())) return;
            if (payload.action().equals("upgrade")) FORGE.upgrade(p, payload.slot(), payload.quality());
            else if (payload.action().equals("craft")) FORGE.craft(p, payload.recipe(), payload.quality());
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.HomeAction.ID, (payload, ctx) -> HOMES.action(ctx.player(), payload.action(), payload.home(), payload.arg()));
        // Doors: into your home in town, back out inside; the anvil of a home forge opens the forge.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp) || hand != net.minecraft.util.Hand.MAIN_HAND) return ActionResult.PASS;
            var state = world.getBlockState(hit.getBlockPos());
            // Property plots: sneak + use on the plot, or use its "For sale" sign.
            if (world.getRegistryKey() == net.minecraft.world.World.OVERWORLD && PROFILES.get(sp.getUuid()).created) {
                boolean sign = state.getBlock() instanceof net.minecraft.block.AbstractSignBlock;
                if ((sign || sp.isSneaking()) && !Homes.isDoor(state) && HOMES.usePlot(sp, hit.getBlockPos(), sign)) return ActionResult.SUCCESS;
            }
            if (Homes.isDoor(state)) {
                if (world.getRegistryKey() == net.minecraft.world.World.OVERWORLD && PROFILES.get(sp.getUuid()).created
                    && HOMES.useDoor(sp, hit.getBlockPos(), sp.isSneaking())) return ActionResult.SUCCESS;
                if (world.getRegistryKey() == Homes.WORLD && HOMES.useHomeDoor(sp, hit.getBlockPos())) return ActionResult.SUCCESS;
            }
            if (world.getRegistryKey() == Homes.WORLD && state.isIn(net.minecraft.registry.tag.BlockTags.ANVIL)
                && HOMES.hasUpgrade(sp, hit.getBlockPos(), Homes.Upgrade.FORGE)) {
                FORGE.open(sp);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.FactionAction.ID, (payload, ctx) -> {
            if (payload.action().equals("open")) FACTIONS.send(ctx.player(), true);
            else FACTIONS.action(ctx.player(), payload.action(), payload.arg());
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.CharacterAction.ID, (payload, ctx) -> {
            if (payload.action().equals("list")) CHARACTERS.send(ctx.player(), true);
            else CHARACTERS.action(ctx.player(), payload.action(), payload.slot());
        });
        // Danny's AoT bloodline reroll: two per character.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            var stack = player.getStackInHand(hand);
            if (!world.isClient && player instanceof ServerPlayerEntity sp && Characters.isRerollItem(stack) && !CHARACTERS.allowReroll(sp)) {
                return net.minecraft.util.TypedActionResult.fail(stack);
            }
            return net.minecraft.util.TypedActionResult.pass(stack);
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.Struggle.ID, (payload, ctx) -> GRAB.strike(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(Net.ToggleSheath.ID, (payload, ctx) -> {
            if (PROFILES.get(ctx.player().getUuid()).created) LOADOUT.toggle(ctx.player());
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.QuickHealUse.ID, (payload, ctx) -> {
            if (PROFILES.get(ctx.player().getUuid()).created) HEAL.use(ctx.player());
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.SelectCosmetic.ID, (payload, ctx) -> COSMETICS.select(ctx.player(), payload.cosmetic()));
        // APG gun shots (left click): the shooter's client reports the shot and draws its own
        // trail at once; everyone else nearby gets it from here.
        ServerPlayNetworking.registerGlobalReceiver(Net.ShotFired.ID, (payload, ctx) -> {
            ServerPlayerEntity sp = ctx.player();
            if (!AotItems.isApgGun(sp.getMainHandStack())) return;
            long now = sp.getServerWorld().getTime();
            Long last = LAST_SHOT.get(sp.getUuid());
            if (last != null && now - last < 3) return;
            LAST_SHOT.put(sp.getUuid(), now);
            var dir = sp.getRotationVec(1f);
            var eye = sp.getEyePos();
            var end2 = eye.add(dir.multiply(96));
            var hit = sp.getWorld().raycast(new net.minecraft.world.RaycastContext(eye, end2, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, sp));
            var to = hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS ? end2 : hit.getPos();
            var start = eye.add(dir.multiply(0.8)).add(0, -0.25, 0);
            Net.Trail t = new Net.Trail(COSMETICS.selected(sp, "trail"), start.x, start.y, start.z, to.x, to.y, to.z);
            for (ServerPlayerEntity o : sp.getServerWorld().getPlayers()) {
                if (o != sp && o.squaredDistanceTo(sp) < 128 * 128 && ServerPlayNetworking.canSend(o, Net.Trail.ID)) ServerPlayNetworking.send(o, t);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.WorldDataRequest.ID, (payload, ctx) -> {
            sendWorldData(ctx.player());
            QUESTS.send(ctx.player());
            QUESTS.markers(ctx.player(), true);
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.QuestAction.ID, (payload, ctx) -> QUESTS.action(ctx.player(), payload.quest(), payload.action()));
        ServerPlayNetworking.registerGlobalReceiver(Net.SetWaypoint.ID, (payload, ctx) -> QUESTS.setWaypoint(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(Net.MapRequest.ID, (payload, ctx) -> {
            byte[] data = PLACES.mapBytes(payload.name());
            if (data == null) return;
            int size = 512 * 1024, total = (data.length + size - 1) / size;
            for (int i = 0; i < total; i++) {
                byte[] part = java.util.Arrays.copyOfRange(data, i * size, Math.min(data.length, (i + 1) * size));
                ServerPlayNetworking.send(ctx.player(), new Net.MapChunk(payload.name(), i, total, part));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.OpenSatchel.ID, (payload, ctx) -> {
            if (PROFILES.get(ctx.player().getUuid()).created) SATCHEL.openScreen(ctx.player());
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.Cook.ID, (payload, ctx) -> COOKING.cook(ctx.player(), payload.recipe(), payload.times()));
        // Right-click a lit campfire with an empty hand (or while sneaking) to cook.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            var state = world.getBlockState(hit.getBlockPos());
            if (!Cooking.isLitCampfire(state)) return ActionResult.PASS;
            if (!player.getMainHandStack().isEmpty() && !player.isSneaking()) return ActionResult.PASS;
            if (!PROFILES.get(sp.getUuid()).created || !hasClient(sp)) return ActionResult.PASS;
            COOKING.open(sp, hit.getBlockPos());
            return ActionResult.SUCCESS;
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            if (!alive && DeathCare.keepsItems(oldPlayer)) newPlayer.getInventory().clone(oldPlayer.getInventory());
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.Create.ID, (payload, ctx) -> CREATION.submit(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(Net.SpendPoint.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Profile pr = PROFILES.get(p.getUuid());
            if (!pr.created || pr.points <= 0 || payload.stat() < 0 || payload.stat() >= Stat.values().length) return;
            pr.points--;
            pr.stats.merge(Stat.values()[payload.stat()], 1, Integer::sum);
            PROGRESSION.apply(p, pr);
            sync(p, pr);
            PROFILES.save(p.getUuid());
            p.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.6f, 1.2f);
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.Learn.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            Profile pr = PROFILES.get(p.getUuid());
            if (!pr.created || payload.skill() < 0 || payload.skill() >= Skill.values().length) return;
            Skill sk = Skill.values()[payload.skill()];
            Skill prev = sk.previous();
            if (pr.has(sk) || pr.skillPoints <= 0 || pr.level < sk.level || (prev != null && !pr.has(prev))) return;
            pr.skillPoints--;
            pr.skills.add(sk);
            PROGRESSION.apply(p, pr);
            sync(p, pr);
            PROFILES.save(p.getUuid());
            p.playSoundToPlayer(SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.MASTER, 0.8f, 1.1f);
            Titles.show(p, Text.empty(), Text.literal("Skill learned: ").formatted(Formatting.GRAY)
                .append(Text.literal(sk.title).formatted(Formatting.GOLD, Formatting.BOLD)), 5, 40, 10);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp) STAMINA.attack(sp, PROFILES.get(sp.getUuid()));
            return ActionResult.PASS;
        });

        // Protected land: no breaking, no buckets or fire, no knocking down frames and paintings.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (CARE.canBuild(player, pos)) return true;
            CARE.deny(player);
            return false;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> CARE.forget(pos));
        UseItemCallback.EVENT.register((player, world, hand) -> {
            var stack = player.getStackInHand(hand);
            if (!world.isClient && (stack.getItem() instanceof net.minecraft.item.BucketItem
                || stack.getItem() instanceof net.minecraft.item.PowderSnowBucketItem) && !CARE.canBuild(player, player.getBlockPos())) {
                CARE.deny(player);
                return net.minecraft.util.TypedActionResult.fail(stack);
            }
            return net.minecraft.util.TypedActionResult.pass(stack);
        });
        // No vanilla chest loot: containers with a loot table are emptied of it before they open.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient && !CARE.config.vanillaChestLoot && world.getRegistryKey() == net.minecraft.world.World.OVERWORLD
                && world.getBlockEntity(hit.getBlockPos()) instanceof net.minecraft.block.entity.LootableContainerBlockEntity lc
                && lc.getLootTable() != null) {
                lc.setLootTable(null);
                lc.markDirty();
            }
            return ActionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            var item = player.getStackInHand(hand).getItem();
            if (!world.isClient && (item instanceof net.minecraft.item.FlintAndSteelItem || item instanceof net.minecraft.item.FireChargeItem
                || item instanceof net.minecraft.item.BucketItem || item instanceof net.minecraft.item.PowderSnowBucketItem
                || item instanceof net.minecraft.item.HoeItem || item instanceof net.minecraft.item.ShovelItem
                || item instanceof net.minecraft.item.AxeItem) && !CARE.canBuild(player, hit.getBlockPos())) {
                // Hoes till, shovels make paths, axes strip logs: all changes to the land.
                CARE.deny(player);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && (entity instanceof net.minecraft.entity.decoration.AbstractDecorationEntity
                || entity instanceof net.minecraft.entity.decoration.ArmorStandEntity) && !CARE.canBuild(player, entity.getBlockPos())) {
                CARE.deny(player);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && (entity instanceof net.minecraft.entity.decoration.ItemFrameEntity
                || entity instanceof net.minecraft.entity.decoration.ArmorStandEntity) && !CARE.canBuild(player, entity.getBlockPos())) {
                CARE.deny(player);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PROFILES.open(server);
            PLACES.load(server);
            SATCHEL.open(server);
            QUESTS.load(server);
            AotItems.scan(server);
            COSMETICS.open(server);
            CARE.open(server);
            MARKET.open(server);
            EXCHANGE.open(server);
            FACTIONS.open(server);
            HOMES.open(server);
            MODES.open(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PROFILES.saveAll();
            SATCHEL.saveAll();
            CARE.save();
            MARKET.save();
            NAMETAGS.clear();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            Profile pr = PROFILES.get(p.getUuid());
            PARTIES.joined(p);
            COSMETICS.sync(p);
            LOADOUT.sendAll(p);
            HomeAdmin.joined(p);
            SCHEDULER.later(60, () -> {
                if (!p.isDisconnected()) HomeAdmin.notify(p);
            });
            sendWorldData(p);
            SCHEDULER.later(20, () -> {
                if (p.isDisconnected()) return;
                WALLET.sync(p);
                boolean several = PROFILES.account(p.getUuid()).slots.size() > 1;
                // Returning players choose their character (the last one played is ready to continue).
                if (pr.created || several) CHARACTERS.send(p, true);
                if (!pr.created) {
                    if (!several || !hasClient(p)) CREATION.begin(p);
                } else {
                    PROGRESSION.apply(p, pr);
                    sync(p, pr);
                    NAMETAGS.update(p, pr);
                    STORY.send(p, pr);
                    p.sendMessage(Text.literal("Welcome back, ").formatted(Formatting.GRAY)
                        .append(Text.literal(pr.name).formatted(Formatting.GOLD, Formatting.BOLD))
                        .append(Text.literal(".").formatted(Formatting.GRAY)), true);
                }
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            if (CREATION.active(p)) {
                CREATION.end(p);
                p.setInvulnerable(false);
            }
            NAMETAGS.remove(p);
            STAMINA.remove(p);
            SATCHEL.unload(p.getUuid());
            QUESTS.forget(p);
            HEAL.forget(p);
            LOADOUT.forget(p);
            WAVES.forget(p);
            ROLES.forget(p);
            HOMES.forget(p);
            GRAB.forget(p);
            PROGRESSION.forgetHunger(p);
            PROGRESSION.removeBar(p);
            PROFILES.unload(p.getUuid());
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            Profile pr = PROFILES.get(newPlayer.getUuid());
            PROGRESSION.removeBar(oldPlayer);
            NAMETAGS.remove(oldPlayer);
            if (!pr.created) return;
            PROGRESSION.apply(newPlayer, pr);
            newPlayer.setHealth(newPlayer.getMaxHealth());
            STAMINA.refill(newPlayer);
            PROGRESSION.forgetHunger(newPlayer);
            sync(newPlayer, pr);
            NAMETAGS.update(newPlayer, pr);
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (NAMETAGS.isStray(entity)) entity.discard();
        });

        ServerTickEvents.END_SERVER_TICK.register(this::tick);

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
            !CREATION.chat(sender, message.getContent().getString()));

        ServerLivingEntityEvents.AFTER_DEATH.register(this::onDeath);

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
            Commands.register(dispatcher);
            PARTIES.register(dispatcher);
        });

        // No friendly fire inside a party.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
            !(entity instanceof ServerPlayerEntity victim && source.getAttacker() instanceof ServerPlayerEntity attacker
                && PARTIES.same(attacker.getUuid(), victim.getUuid())));
        COMBAT.register();
    }

    private void tick(MinecraftServer server) {
        SCHEDULER.tick();
        ticks++;
        CARE.tick(ticks, false);
        MARKET.tick(ticks);
        EXCHANGE.tick(ticks);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            CREATION.tick(p);
            STAMINA.tick(p, PROFILES.get(p.getUuid()), ticks);
            STORY.tick(p, PROFILES.get(p.getUuid()), ticks);
            QUESTS.tick(p, PROFILES.get(p.getUuid()), ticks);
            if (PROFILES.get(p.getUuid()).created) LOADOUT.tick(p, ticks);
            WAVES.tick(p, ticks);
            if (PROFILES.get(p.getUuid()).created) ROLES.tick(p, ticks);
            GRAB.tick(p, ticks);
            if (ticks % 5 == 0 && PROFILES.get(p.getUuid()).created) {
                SATCHEL.tickSupplies(p);
                HEAL.sync(p, ticks % 40 == 0);
            }
            if (ticks % 20 == 0 && PROFILES.get(p.getUuid()).created) PROGRESSION.hunger(p);
        }
        PARTIES.tick(server, ticks);
        GUARD.tick(server, ticks);
        NAMETAGS.tick(server, ticks);
        if (ticks % (20 * 300) == 0) {
            PROFILES.saveAll();
            SATCHEL.saveAll();
        }
    }

    public static boolean isTitan(Entity e) {
        if (e.getCommandTags().contains("aot_titan")) return true;
        String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        return id.contains("titan");
    }

    private void onDeath(LivingEntity dead, net.minecraft.entity.damage.DamageSource source) {
        if (dead instanceof ServerPlayerEntity sp) {
            DEATH.onDeath(sp, source);
            return;
        }
        if (!isTitan(dead)) return;
        Entity attacker = source.getAttacker();
        if (!(attacker instanceof ServerPlayerEntity killer)) return;
        long xp = Math.max(15, Math.round(10 + dead.getMaxHealth() / 4));
        if (PROFILES.get(killer.getUuid()).has(Skill.TITAN_SLAYER)) xp = Math.round(xp * 1.25);
        reward(killer, xp, true, "Titan slain");
        // A bounty in Marks, and maybe gear (bosses and shifters always drop).
        WALLET.earn(killer, 4 + Math.round(dead.getMaxHealth() / 40), null);
        GEAR.titanDrop(killer, dead, PLACES.levelAt(dead.getX(), dead.getZ()));
        QUESTS.onTitanKill(killer, dead.getX(), dead.getZ());
        FACTIONS.onTitanKill(killer, dead.getX(), dead.getZ());
        // Party members within 64 blocks share 60%; anyone else within 32 blocks gets an assist.
        for (ServerPlayerEntity p : killer.getServerWorld().getPlayers()) {
            if (p == killer) continue;
            double d2 = p.squaredDistanceTo(dead);
            if (PARTIES.same(killer.getUuid(), p.getUuid()) && d2 < 64 * 64) {
                reward(p, Math.round(xp * 0.6), false, "Party kill");
                QUESTS.onTitanKill(p, dead.getX(), dead.getZ());
            }
            else if (d2 < 32 * 32) reward(p, xp / 2, false, "Assist");
        }
    }

    private void reward(ServerPlayerEntity p, long xp, boolean killer, String label) {
        Profile pr = PROFILES.get(p.getUuid());
        if (!pr.created) return;
        if (killer) pr.titanKills++;
        p.sendMessage(Text.literal("+" + xp + " XP  ").formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal(label).formatted(Formatting.RED)), true);
        if (killer) p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1.4f);
        PROGRESSION.addXp(p, pr, xp);
    }
}
