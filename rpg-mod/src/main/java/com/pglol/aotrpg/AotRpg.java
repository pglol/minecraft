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

    /** True if this player runs the mod on their client (custom screens and HUD). */
    public static boolean hasClient(ServerPlayerEntity p) {
        return ServerPlayNetworking.canSend(p, Net.Sync.ID);
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

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PROFILES.open(server);
            PLACES.load(server);
            SATCHEL.open(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PROFILES.saveAll();
            SATCHEL.saveAll();
            NAMETAGS.clear();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            Profile pr = PROFILES.get(p.getUuid());
            PARTIES.joined(p);
            if (ServerPlayNetworking.canSend(p, Net.Campfires.ID)) ServerPlayNetworking.send(p, new Net.Campfires(PLACES.campfireArray()));
            SCHEDULER.later(20, () -> {
                if (p.isDisconnected()) return;
                if (!pr.created) {
                    CREATION.begin(p);
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
            PROGRESSION.forgetHunger(p);
            PROGRESSION.removeBar(p);
            PROFILES.save(p.getUuid());
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
    }

    private void tick(MinecraftServer server) {
        SCHEDULER.tick();
        ticks++;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            CREATION.tick(p);
            STAMINA.tick(p, PROFILES.get(p.getUuid()), ticks);
            STORY.tick(p, PROFILES.get(p.getUuid()), ticks);
            if (ticks % 20 == 0 && PROFILES.get(p.getUuid()).created) PROGRESSION.hunger(p);
        }
        PARTIES.tick(server, ticks);
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
        // Party members within 64 blocks share 60%; anyone else within 32 blocks gets an assist.
        for (ServerPlayerEntity p : killer.getServerWorld().getPlayers()) {
            if (p == killer) continue;
            double d2 = p.squaredDistanceTo(dead);
            if (PARTIES.same(killer.getUuid(), p.getUuid()) && d2 < 64 * 64) reward(p, Math.round(xp * 0.6), false, "Party kill");
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
