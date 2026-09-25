package com.pglol.aotrpg;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** /character for players, /aotrpg for operators. */
final class Commands {
    private Commands() {}

    static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("character")
            .then(CommandManager.literal("reset")
                .executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    if (!AotRpg.PROFILES.get(p.getUuid()).created) {
                        if (!AotRpg.CREATION.active(p)) AotRpg.CREATION.begin(p);
                        return 0;
                    }
                    p.sendMessage(Text.literal("\u26a0 This deletes your character: name, level, stats and skills. ").formatted(Formatting.RED)
                        .append(Text.literal("[Click to confirm]").formatted(Formatting.GOLD, Formatting.BOLD)
                            .styled(st -> st.withClickEvent(new net.minecraft.text.ClickEvent(
                                net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/character reset confirm"))
                                .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                    Text.literal("Start character creation again"))))));
                    return 1;
                })
                .then(CommandManager.literal("confirm").executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    reset(p, true);
                    return 1;
                })))
            .executes(c -> {
            ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            if (!pr.created) {
                if (!AotRpg.CREATION.active(p)) AotRpg.CREATION.begin(p);
                return 0;
            }
            if (AotRpg.hasClient(p)) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Net.OpenCharacter(0));
            else sheet(p, pr);
            return 1;
        }));

        d.register(CommandManager.literal("aotrpg").requires(s -> s.hasPermissionLevel(2))
            .then(CommandManager.literal("reset")
                .executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    reset(p, false);
                    c.getSource().sendFeedback(() -> Text.literal("Reset your character."), true);
                    return 1;
                })
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    reset(p, false);
                    c.getSource().sendFeedback(() -> Text.literal("Reset " + p.getName().getString() + "'s character."), true);
                    return 1;
                })))
            .then(CommandManager.literal("setlevel").then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("level", IntegerArgumentType.integer(1, Progression.MAX_LEVEL)).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    Profile pr = created(p);
                    int lv = IntegerArgumentType.getInteger(c, "level");
                    pr.points += lv - pr.level;
                    if (pr.points < 0) pr.points = 0;
                    pr.skillPoints += Skill.pointsForLevel(lv) - Skill.pointsForLevel(pr.level);
                    if (pr.skillPoints < 0) pr.skillPoints = 0;
                    pr.level = lv;
                    pr.xp = 0;
                    refresh(p, pr);
                    c.getSource().sendFeedback(() -> Text.literal(pr.name + " is now level " + lv + "."), true);
                    return 1;
                }))))
            .then(CommandManager.literal("xp").then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    Profile pr = created(p);
                    AotRpg.PROGRESSION.addXp(p, pr, IntegerArgumentType.getInteger(c, "amount"));
                    AotRpg.PROFILES.save(p.getUuid());
                    return 1;
                }))))
            .then(CommandManager.literal("mode").then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.literal("story").executes(c -> setMode(c.getSource(), EntityArgumentType.getPlayer(c, "player"), DeathCare.STORY)))
                .then(CommandManager.literal("extraction").executes(c -> setMode(c.getSource(), EntityArgumentType.getPlayer(c, "player"), DeathCare.EXTRACTION)))))
            .then(CommandManager.literal("campfire").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                var pos = p.getBlockPos();
                p.getServerWorld().setBlockState(pos, net.minecraft.block.Blocks.CAMPFIRE.getDefaultState());
                AotRpg.PLACES.addCampfire(pos.getX(), pos.getY(), pos.getZ());
                var fires = new Net.Campfires(AotRpg.PLACES.campfireArray());
                for (ServerPlayerEntity o : c.getSource().getServer().getPlayerManager().getPlayerList()) {
                    if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(o, Net.Campfires.ID))
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(o, fires);
                }
                c.getSource().sendFeedback(() -> Text.literal("Placed a cooking campfire here."), true);
                return 1;
            }))
            .then(CommandManager.literal("kit").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
                ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                Profile pr = created(p);
                Kit.give(p, pr, AotRpg.PLACES.get("cadet-training-camp"));
                c.getSource().sendFeedback(() -> Text.literal("Gave " + pr.name + " the starter kit."), true);
                return 1;
            })))
            .then(CommandManager.literal("patch").then(CommandManager.literal("underground").executes(c -> {
                // Closes the open trench over the Underground City stairway in worlds made before the fix:
                // roof over the tunnel, stone up to street level, paving on top. Matches the generator layout.
                var w = c.getSource().getServer().getOverworld();
                int base = 72, caveFloor = 10, x0 = 258, zc = 20, changed = 0;
                var roof = net.minecraft.block.Blocks.STONE_BRICKS.getDefaultState();
                var paving = net.minecraft.block.Blocks.POLISHED_ANDESITE.getDefaultState();
                for (int x = x0; x <= 312; x++) {
                    int floor = caveFloor + (x - x0);
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int y = floor + 6; y <= base; y++) {
                            w.setBlockState(new net.minecraft.util.math.BlockPos(x, y, zc + dz), y == base ? paving : roof);
                            changed++;
                        }
                        for (int y = base + 1; y <= base + 3; y++) {
                            w.setBlockState(new net.minecraft.util.math.BlockPos(x, y, zc + dz), net.minecraft.block.Blocks.AIR.getDefaultState());
                        }
                    }
                }
                int n = changed;
                c.getSource().sendFeedback(() -> Text.literal("Closed the Underground stairway trench (" + n + " blocks). The stairs below are untouched."), true);
                return 1;
            })))
            .then(CommandManager.literal("items").executes(c -> {
                AotItems.scan(c.getSource().getServer());
                c.getSource().sendFeedback(() -> Text.literal(AotItems.all().size() + " AoT mod items listed in <world>/aot_rpg/aot-items.txt"), false);
                return 1;
            }))
            .then(CommandManager.literal("reload").executes(c -> {
                AotRpg.PLACES.load(c.getSource().getServer());
                AotRpg.QUESTS.load(c.getSource().getServer());
                for (ServerPlayerEntity o : c.getSource().getServer().getPlayerManager().getPlayerList()) {
                    AotRpg.sendWorldData(o);
                    AotRpg.QUESTS.send(o);
                }
                c.getSource().sendFeedback(() -> Text.literal("Reloaded aot-rpg.json."), true);
                return 1;
            })));
    }

    private static int setMode(ServerCommandSource src, ServerPlayerEntity p, String mode) {
        AotRpg.PROFILES.get(p.getUuid()).mode = mode;
        AotRpg.PROFILES.save(p.getUuid());
        p.sendMessage(Text.literal(mode.equals(DeathCare.STORY)
            ? "Story mode: your gear is protected when you die."
            : "Extraction mode: you drop your gear when you die. Your satchel is always safe.").formatted(Formatting.GOLD));
        src.sendFeedback(() -> Text.literal(p.getName().getString() + " is now in " + mode + " mode."), true);
        return 1;
    }

    /** Wipes a character and opens the creator again. keepKit: no second starter kit. */
    static void reset(ServerPlayerEntity p, boolean keepKit) {
        AotRpg.PROFILES.reset(p.getUuid(), keepKit);
        AotRpg.NAMETAGS.remove(p);
        AotRpg.PROGRESSION.removeBar(p);
        AotRpg.STAMINA.refill(p);
        AotRpg.PROGRESSION.apply(p, AotRpg.PROFILES.get(p.getUuid()));
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
        AotRpg.CREATION.begin(p);
    }

    private static Profile created(ServerPlayerEntity p) throws CommandSyntaxException {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
            Text.literal(p.getName().getString() + " has not created a character yet.")).create();
        return pr;
    }

    private static void refresh(ServerPlayerEntity p, Profile pr) {
        AotRpg.PROGRESSION.apply(p, pr);
        AotRpg.sync(p, pr);
        AotRpg.NAMETAGS.update(p, pr);
        AotRpg.PROFILES.save(p.getUuid());
    }

    /** The character sheet: stats, and spending points. */
    static void sheet(ServerPlayerEntity p, Profile pr) {
        Menu m = new Menu(Text.literal(pr.name).formatted(Formatting.DARK_RED, Formatting.BOLD), 4);
        m.button(4, Menu.glow(Menu.icon(Items.PLAYER_HEAD, Menu.line(pr.name, Formatting.GOLD, Formatting.BOLD),
            Menu.line("Level " + pr.level + " " + pr.discipline.title, Formatting.YELLOW),
            Menu.line("Origin: " + pr.origin.title, Formatting.GRAY),
            Menu.line(pr.level >= Progression.MAX_LEVEL ? "Max level" : "XP: " + pr.xp + " / " + Profile.xpForNext(pr.level), Formatting.GRAY),
            Menu.line("Titans slain: " + pr.titanKills, Formatting.RED),
            Menu.line("Chapter " + pr.chapter, Formatting.DARK_GRAY))), null);
        int[] cols = {1, 3, 5, 7};
        Stat[] all = Stat.values();
        for (int i = 0; i < all.length; i++) {
            Stat st = all[i];
            ItemStack it = Menu.icon(st.icon, Menu.line(st.title + ": " + pr.total(st), Formatting.GOLD),
                Menu.line(st.effect, Formatting.GRAY));
            it.setCount(Math.max(1, Math.min(99, pr.total(st))));
            m.button(18 + cols[i], it, null);
            if (pr.points > 0) m.button(27 + cols[i], Menu.icon(Items.LIME_STAINED_GLASS_PANE,
                Menu.line("+1 " + st.title, Formatting.GREEN), Menu.line(pr.points + " point(s) to spend", Formatting.GRAY)), pl -> {
                    if (pr.points <= 0) return;
                    pr.points--;
                    pr.stats.merge(st, 1, Integer::sum);
                    refresh(pl, pr);
                    pl.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.6f, 1.2f);
                    sheet(pl, pr);
                });
        }
        m.open(p);
    }
}
