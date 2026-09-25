package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Several characters per player. Each character owns everything: inventory, armour, ender chest,
 * health, hunger, position and spawn point, other mods' player data, the satchel and sheath,
 * quests, stats, Marks. Switching saves the whole player into the old character's file and loads
 * the new one's.
 */
public final class Characters {
    public static final int MAX_REROLLS = 2;

    private ProfileStore store() {
        return AotRpg.PROFILES;
    }

    private Path stateFile(UUID id, int slot) {
        return store().dir().resolve(ProfileStore.stem(id, slot) + ".state.dat");
    }

    /** The character list for the select screen. */
    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.CharacterList.ID)) return;
        ProfileStore.Account a = store().account(p.getUuid());
        List<Net.CharacterEntry> list = new ArrayList<>();
        for (int slot : a.slots) {
            Profile pr = store().peek(p.getUuid(), slot);
            list.add(new Net.CharacterEntry(slot, pr.created, pr.created ? pr.name : "", pr.level,
                pr.discipline == null ? -1 : pr.discipline.ordinal(), pr.origin == null ? -1 : pr.origin.ordinal(),
                pr.marks, pr.bloodlineRerolls, a.lastPlayed.getOrDefault(slot, 0L)));
        }
        ServerPlayNetworking.send(p, new Net.CharacterList(list, a.active, ProfileStore.MAX_CHARACTERS, MAX_REROLLS, open));
    }

    /** Characters can only be changed out of combat and outside creation. */
    private boolean calm(ServerPlayerEntity p) {
        if (AotRpg.CREATION.active(p) && store().account(p.getUuid()).slots.size() <= 1) return false;
        if (p.hurtTime > 0 || p.getRecentDamageSource() != null) {
            p.sendMessage(Text.literal("You can't change character in the middle of a fight.").formatted(Formatting.RED), true);
            return false;
        }
        return true;
    }

    public void action(ServerPlayerEntity p, String action, int slot) {
        UUID id = p.getUuid();
        ProfileStore.Account a = store().account(id);
        switch (action) {
            case "play" -> {
                if (!a.slots.contains(slot)) return;
                if (slot == a.active) {
                    // Continue as the current character.
                    if (!store().get(id).created) AotRpg.CREATION.begin(p);
                    return;
                }
                if (calm(p)) switchTo(p, slot);
            }
            case "new" -> {
                if (!calm(p)) return;
                int n = store().newSlot(id);
                if (n < 0) {
                    p.sendMessage(Text.literal("You already have " + ProfileStore.MAX_CHARACTERS + " characters.").formatted(Formatting.RED), true);
                    return;
                }
                switchTo(p, n);
            }
            case "delete" -> {
                if (!a.slots.contains(slot)) return;
                if (slot == a.active) {
                    if (a.slots.size() > 1) {
                        p.sendMessage(Text.literal("Switch to another character before deleting this one.").formatted(Formatting.RED), true);
                        return;
                    }
                    // The only character: start over (no second starter kit).
                    Commands.reset(p, true);
                } else {
                    Path satchel = AotRpg.SATCHEL.fileFor(ProfileStore.stem(id, slot));
                    store().delete(id, slot);
                    try {
                        Files.deleteIfExists(satchel);
                    } catch (Exception e) {
                        AotRpg.LOG.error("Could not delete {}", satchel, e);
                    }
                    p.sendMessage(Text.literal("Character deleted.").formatted(Formatting.GRAY), true);
                }
            }
            default -> { }
        }
        send(p, false);
    }

    /** Saves the live player into the current character and loads another one. */
    public void switchTo(ServerPlayerEntity p, int slot) {
        UUID id = p.getUuid();
        int old = store().account(id).active;
        if (old == slot) return;
        // Leave everything tidy: sheath and stash back into the inventory so they travel with it.
        AotRpg.LOADOUT.unsheathAll(p);
        if (AotRpg.CREATION.active(p)) {
            AotRpg.CREATION.end(p);
            p.setInvulnerable(false);
        }
        saveState(p, old);
        AotRpg.SATCHEL.unload(id);
        AotRpg.QUESTS.forget(p);
        AotRpg.HEAL.forget(p);
        AotRpg.NAMETAGS.remove(p);
        AotRpg.PROGRESSION.removeBar(p);

        store().activate(id, slot);
        Profile pr = store().get(id);
        if (!loadState(p, slot)) blank(p);

        AotRpg.PROGRESSION.apply(p, pr);
        AotRpg.STAMINA.refill(p);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 0.4f, 1.4f);
        if (!pr.created) {
            AotRpg.CREATION.begin(p);
        } else {
            AotRpg.sync(p, pr);
            AotRpg.NAMETAGS.update(p, pr);
            AotRpg.STORY.send(p, pr);
            AotRpg.QUESTS.send(p);
            AotRpg.WALLET.sync(p);
            p.sendMessage(Text.literal("Now playing ").formatted(Formatting.GRAY)
                .append(Text.literal(pr.name).formatted(Formatting.GOLD, Formatting.BOLD)), true);
        }
        AotRpg.LOADOUT.broadcast(p, true);
    }

    private void saveState(ServerPlayerEntity p, int slot) {
        try {
            NbtCompound n = new NbtCompound();
            p.writeNbt(n);
            n.putString("aot_dim", p.getWorld().getRegistryKey().getValue().toString());
            if (p.getSpawnPointPosition() != null) {
                n.putLong("aot_spawn", p.getSpawnPointPosition().asLong());
                n.putString("aot_spawn_dim", p.getSpawnPointDimension().getValue().toString());
                n.putFloat("aot_spawn_angle", p.getSpawnAngle());
                n.putBoolean("aot_spawn_forced", p.isSpawnForced());
            }
            NbtIo.writeCompressed(n, stateFile(p.getUuid(), slot));
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save character state for {}", p.getName().getString(), e);
        }
    }

    /** Loads a saved character into the live player. False when this character has no saved state. */
    private boolean loadState(ServerPlayerEntity p, int slot) {
        Path f = stateFile(p.getUuid(), slot);
        if (!Files.exists(f)) return false;
        try {
            NbtCompound n = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
            p.clearStatusEffects();
            p.getInventory().clear();
            p.getEnderChestInventory().clear();
            p.readNbt(n);
            // Effects were read without telling the client: re-apply them properly.
            List<StatusEffectInstance> effects = new ArrayList<>();
            for (StatusEffectInstance e : p.getStatusEffects()) effects.add(new StatusEffectInstance(e));
            p.clearStatusEffects();
            for (StatusEffectInstance e : effects) p.addStatusEffect(e);

            ServerWorld w = world(p, n.getString("aot_dim"));
            p.teleport(w, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
            if (n.contains("aot_spawn")) {
                p.setSpawnPoint(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(n.getString("aot_spawn_dim"))),
                    BlockPos.fromLong(n.getLong("aot_spawn")), n.getFloat("aot_spawn_angle"), n.getBoolean("aot_spawn_forced"), false);
            } else {
                p.setSpawnPoint(World.OVERWORLD, null, 0, false, false);
            }
            resync(p);
            return true;
        } catch (Exception e) {
            AotRpg.LOG.error("Could not load character state {}", f, e);
            return false;
        }
    }

    /** A brand new character: nothing carried over, back at the world spawn. */
    private void blank(ServerPlayerEntity p) {
        p.clearStatusEffects();
        p.getInventory().clear();
        p.getEnderChestInventory().clear();
        p.setExperienceLevel(0);
        p.setExperiencePoints(0);
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);
        p.getHungerManager().setSaturationLevel(5);
        p.extinguish();
        p.fallDistance = 0;
        ServerWorld w = p.getServer().getOverworld();
        BlockPos s = w.getSpawnPos();
        p.teleport(w, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0, 0);
        p.setSpawnPoint(World.OVERWORLD, null, 0, false, false);
        resync(p);
    }

    private static ServerWorld world(ServerPlayerEntity p, String dim) {
        if (dim != null && !dim.isEmpty()) {
            ServerWorld w = p.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dim)));
            if (w != null) return w;
        }
        return p.getServer().getOverworld();
    }

    private static void resync(ServerPlayerEntity p) {
        p.playerScreenHandler.syncState();
        p.markHealthDirty();
        p.setExperienceLevel(p.experienceLevel);
        p.sendAbilitiesUpdate();
    }

    // ------------------------------------------------------------------ bloodline rerolls

    /** Danny's AoT bloodline reroll item: at most MAX_REROLLS uses per character. */
    public boolean allowReroll(ServerPlayerEntity p) {
        Profile pr = store().get(p.getUuid());
        if (pr.bloodlineRerolls >= MAX_REROLLS) {
            p.sendMessage(Text.literal("This character has used both bloodline rerolls.").formatted(Formatting.RED), true);
            return false;
        }
        pr.bloodlineRerolls++;
        store().save(p.getUuid());
        int left = MAX_REROLLS - pr.bloodlineRerolls;
        p.sendMessage(Text.literal("Bloodline rerolled. " + left + (left == 1 ? " reroll" : " rerolls") + " left for this character.")
            .formatted(Formatting.GOLD), true);
        return true;
    }

    public static boolean isRerollItem(net.minecraft.item.ItemStack s) {
        Identifier id = Registries.ITEM.getId(s.getItem());
        return id.getPath().equals("bloodline_reroll") && (id.getNamespace().equals("dannys-aot") || id.getNamespace().equals(AotItems.namespace));
    }
}
