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
import net.minecraft.entity.Entity;
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

/** Attack on Titan RPG: character creation, levels and stats. Runs on the server. */
public final class AotRpg implements ModInitializer {
    public static final String MOD_ID = "aot_rpg";
    public static final Logger LOG = LoggerFactory.getLogger("AoT RPG");

    public static final ProfileStore PROFILES = new ProfileStore();
    public static final Places PLACES = new Places();
    public static final Scheduler SCHEDULER = new Scheduler();
    public static final Progression PROGRESSION = new Progression();
    public static final CharacterCreation CREATION = new CharacterCreation();
    public static final Nametags NAMETAGS = new Nametags();

    private int ticks;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PROFILES.open(server);
            PLACES.load(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PROFILES.saveAll();
            NAMETAGS.clear();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            Profile pr = PROFILES.get(p.getUuid());
            SCHEDULER.later(20, () -> {
                if (p.isDisconnected()) return;
                if (!pr.created) {
                    CREATION.begin(p);
                } else {
                    PROGRESSION.apply(p, pr);
                    PROGRESSION.updateBar(p, pr);
                    NAMETAGS.update(p, pr);
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
            PROGRESSION.updateBar(newPlayer, pr);
            NAMETAGS.update(newPlayer, pr);
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (NAMETAGS.isStray(entity)) entity.discard();
        });

        ServerTickEvents.END_SERVER_TICK.register(this::tick);

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
            !CREATION.chat(sender, message.getContent().getString()));

        ServerLivingEntityEvents.AFTER_DEATH.register(this::onDeath);

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> Commands.register(dispatcher));
    }

    private void tick(MinecraftServer server) {
        SCHEDULER.tick();
        ticks++;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            CREATION.tick(p);
            // Once a second: re-attach name tags lost to teleports, dimension changes or dismounts.
            if (ticks % 20 == 0) {
                Profile pr = PROFILES.get(p.getUuid());
                if (pr.created && p.isAlive()) NAMETAGS.update(p, pr);
            }
        }
        if (ticks % (20 * 300) == 0) PROFILES.saveAll();
    }

    public static boolean isTitan(Entity e) {
        if (e.getCommandTags().contains("aot_titan")) return true;
        String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        return id.contains("titan");
    }

    private void onDeath(LivingEntity dead, net.minecraft.entity.damage.DamageSource source) {
        if (!isTitan(dead)) return;
        Entity attacker = source.getAttacker();
        if (!(attacker instanceof ServerPlayerEntity killer)) return;
        long xp = Math.max(15, Math.round(10 + dead.getMaxHealth() / 4));
        reward(killer, xp, true);
        // Squadmates close by share the kill.
        for (ServerPlayerEntity p : killer.getServerWorld().getPlayers()) {
            if (p != killer && p.squaredDistanceTo(dead) < 32 * 32) reward(p, xp / 2, false);
        }
    }

    private void reward(ServerPlayerEntity p, long xp, boolean killer) {
        Profile pr = PROFILES.get(p.getUuid());
        if (!pr.created) return;
        if (killer) pr.titanKills++;
        p.sendMessage(Text.literal("+" + xp + " XP  ").formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal(killer ? "Titan slain" : "Assist").formatted(Formatting.RED)), true);
        if (killer) p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1.4f);
        PROGRESSION.addXp(p, pr, xp);
    }
}
