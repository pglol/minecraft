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
        economy(d);
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
                .then(CommandManager.literal("extraction").executes(c -> setMode(c.getSource(), EntityArgumentType.getPlayer(c, "player"), DeathCare.EXTRACTION)))
                .then(CommandManager.literal("unlock").then(CommandManager.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    String m = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mode");
                    AotRpg.MODES.unlock(p, m);
                    c.getSource().sendFeedback(() -> Text.literal("Unlocked " + m + " for " + p.getName().getString()), true);
                    return 1;
                })))))
            .then(CommandManager.literal("pass")
                .then(CommandManager.literal("reload").executes(c -> {
                    AotRpg.SEASON.reload();
                    AotRpg.EVENTS.reload();
                    for (ServerPlayerEntity o : c.getSource().getServer().getPlayerManager().getPlayerList()) {
                        AotRpg.SEASON.send(o, false);
                        AotRpg.EVENTS.send(o, false);
                    }
                    c.getSource().sendFeedback(() -> Text.literal("Reloaded season.json and event.json"), true);
                    return 1;
                }))
                .then(CommandManager.literal("xp").then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1)).executes(c -> {
                        ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                        long n = com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "amount");
                        AotRpg.SEASON.xp(p, n);
                        AotRpg.SEASON.send(p, false);
                        c.getSource().sendFeedback(() -> Text.literal("Gave " + n + " pass XP to " + p.getName().getString()), true);
                        return 1;
                    }))))
                .then(CommandManager.literal("premium").then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c -> {
                        ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                        boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "on");
                        AotRpg.SEASON.setPremium(p, on);
                        c.getSource().sendFeedback(() -> Text.literal((on ? "Granted" : "Removed") + " premium pass for " + p.getName().getString()), true);
                        return 1;
                    })))))
            .then(CommandManager.literal("tokens").then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg()).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    long n = com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "amount");
                    AotRpg.EVENTS.addTokens(p, n);
                    AotRpg.EVENTS.send(p, false);
                    c.getSource().sendFeedback(() -> Text.literal("Gave " + n + " event tokens to " + p.getName().getString()), true);
                    return 1;
                }))))
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
            .then(CommandManager.literal("patch").then(CommandManager.literal("plots").executes(c -> {
                int n = HomePlots.patchAll(c.getSource().getServer().getOverworld());
                c.getSource().sendFeedback(() -> Text.literal("Cleared " + n + " old fence blocks around unsold plots."), true);
                return 1;
            })).then(CommandManager.literal("underground").executes(c -> {
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
            .then(CommandManager.literal("protect")
                .then(CommandManager.literal("on").executes(c -> protect(c.getSource(), true)))
                .then(CommandManager.literal("off").executes(c -> protect(c.getSource(), false)))
                .then(CommandManager.literal("zone")
                    .then(CommandManager.literal("add").then(CommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(CommandManager.argument("x1", IntegerArgumentType.integer()).then(CommandManager.argument("z1", IntegerArgumentType.integer())
                        .then(CommandManager.argument("x2", IntegerArgumentType.integer()).then(CommandManager.argument("z2", IntegerArgumentType.integer())
                        .executes(c -> {
                            WorldCare.Zone z = new WorldCare.Zone();
                            z.name = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name");
                            z.x1 = IntegerArgumentType.getInteger(c, "x1");
                            z.z1 = IntegerArgumentType.getInteger(c, "z1");
                            z.x2 = IntegerArgumentType.getInteger(c, "x2");
                            z.z2 = IntegerArgumentType.getInteger(c, "z2");
                            AotRpg.CARE.config.buildZones.removeIf(o -> o.name.equalsIgnoreCase(z.name));
                            AotRpg.CARE.config.buildZones.add(z);
                            AotRpg.CARE.saveConfig();
                            c.getSource().sendFeedback(() -> Text.literal("Build zone " + z.name + " added: players can build from "
                                + z.x1 + "," + z.z1 + " to " + z.x2 + "," + z.z2 + ". It does not regenerate."), true);
                            return 1;
                        })))))))
                    .then(CommandManager.literal("remove").then(CommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((c, b) -> {
                            for (WorldCare.Zone z : AotRpg.CARE.config.buildZones) b.suggest(z.name);
                            return b.buildFuture();
                        })
                        .executes(c -> {
                            String n = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name");
                            boolean ok = AotRpg.CARE.config.buildZones.removeIf(o -> o.name.equalsIgnoreCase(n));
                            AotRpg.CARE.saveConfig();
                            c.getSource().sendFeedback(() -> Text.literal(ok ? "Removed build zone " + n + "." : "No build zone named " + n + "."), true);
                            return ok ? 1 : 0;
                        })))
                    .then(CommandManager.literal("list").executes(c -> {
                        StringBuilder sb = new StringBuilder("Protection " + (AotRpg.CARE.config.protect ? "ON" : "OFF") + ". Build zones:");
                        if (AotRpg.CARE.config.buildZones.isEmpty()) sb.append(" none");
                        for (WorldCare.Zone z : AotRpg.CARE.config.buildZones) {
                            sb.append("\n  ").append(z.name).append(": ").append(z.x1).append(",").append(z.z1).append(" to ").append(z.x2).append(",").append(z.z2);
                        }
                        c.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                        return 1;
                    }))))
            .then(CommandManager.literal("regen")
                .then(CommandManager.literal("on").executes(c -> regen(c.getSource(), true)))
                .then(CommandManager.literal("off").executes(c -> regen(c.getSource(), false)))
                .then(CommandManager.literal("delay").then(CommandManager.argument("seconds", IntegerArgumentType.integer(10, 86400)).executes(c -> {
                    AotRpg.CARE.config.regenDelaySeconds = IntegerArgumentType.getInteger(c, "seconds");
                    AotRpg.CARE.saveConfig();
                    c.getSource().sendFeedback(() -> Text.literal("Destroyed blocks now regrow after " + AotRpg.CARE.config.regenDelaySeconds + " seconds."), true);
                    return 1;
                })))
                .then(CommandManager.literal("now").executes(c -> {
                    int n = AotRpg.CARE.pending();
                    AotRpg.CARE.tick(0, true);
                    int left = AotRpg.CARE.pending();
                    c.getSource().sendFeedback(() -> Text.literal("Restored " + (n - left) + " blocks" + (left > 0 ? " (" + left + " in unloaded chunks wait)." : ".")), true);
                    return 1;
                }))
                .then(CommandManager.literal("forget").executes(c -> {
                    int n = AotRpg.CARE.pending();
                    AotRpg.CARE.forgetAll();
                    c.getSource().sendFeedback(() -> Text.literal("Forgot " + n + " destroyed blocks: the damage is now permanent."), true);
                    return 1;
                }))
                .then(CommandManager.literal("status").executes(c -> {
                    var cf = AotRpg.CARE.config;
                    c.getSource().sendFeedback(() -> Text.literal("Regeneration " + (cf.regen ? "ON" : "OFF") + ", delay " + cf.regenDelaySeconds
                        + "s, " + AotRpg.CARE.pending() + " blocks waiting. Protection " + (cf.protect ? "ON" : "OFF") + "."), false);
                    return 1;
                })))
            .then(CommandManager.literal("cosmetics")
                .then(CommandManager.literal("allow").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    AotRpg.COSMETICS.allow(p, true);
                    c.getSource().sendFeedback(() -> Text.literal(p.getName().getString() + " is on the cosmetics allowlist (all unlocked)."), true);
                    return 1;
                })))
                .then(CommandManager.literal("disallow").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    AotRpg.COSMETICS.allow(p, false);
                    c.getSource().sendFeedback(() -> Text.literal(p.getName().getString() + " removed from the cosmetics allowlist."), true);
                    return 1;
                })))
                .then(CommandManager.literal("grant").then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((c, b) -> {
                            b.suggest("all");
                            for (Cosmetics.Def def : Cosmetics.ALL) b.suggest(def.id());
                            return b.buildFuture();
                        })
                        .executes(c -> cosmetic(c.getSource(), EntityArgumentType.getPlayer(c, "player"),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id"), true)))))
                .then(CommandManager.literal("revoke").then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((c, b) -> {
                            b.suggest("all");
                            for (Cosmetics.Def def : Cosmetics.ALL) b.suggest(def.id());
                            return b.buildFuture();
                        })
                        .executes(c -> cosmetic(c.getSource(), EntityArgumentType.getPlayer(c, "player"),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id"), false)))))
                .then(CommandManager.literal("list").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    c.getSource().sendFeedback(() -> Text.literal(p.getName().getString() + (AotRpg.COSMETICS.allowlisted(p) ? " (allowlisted)" : "")
                        + ": " + String.join(", ", AotRpg.COSMETICS.unlocked(p))), false);
                    return 1;
                }))))
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

    /** /wallet, /pay, /characters and the operator money commands. */
    private static void economy(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("furniture").executes(c -> {
            AotRpg.FURNITURE.send(c.getSource().getPlayerOrThrow(), true);
            return 1;
        }));
        d.register(CommandManager.literal("pass").executes(c -> {
            AotRpg.SEASON.send(c.getSource().getPlayerOrThrow(), true);
            return 1;
        }));
        d.register(CommandManager.literal("event").executes(c -> {
            AotRpg.EVENTS.send(c.getSource().getPlayerOrThrow(), true);
            return 1;
        }));
        d.register(CommandManager.literal("social").executes(c -> {
            AotRpg.SOCIAL.send(c.getSource().getPlayerOrThrow(), true);
            return 1;
        }));
        d.register(CommandManager.literal("friend")
            .then(CommandManager.literal("add").then(CommandManager.argument("who", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                String who = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "who");
                java.util.UUID id = null;
                try {
                    id = java.util.UUID.fromString(who);
                } catch (IllegalArgumentException e) {
                    ServerPlayerEntity o = c.getSource().getServer().getPlayerManager().getPlayer(who);
                    if (o != null) id = o.getUuid();
                }
                if (id == null) {
                    c.getSource().sendError(Text.literal("No such player online."));
                    return 0;
                }
                AotRpg.SOCIAL.action(p, "friend_add", id);
                return 1;
            })))
            .then(CommandManager.literal("remove").then(CommandManager.argument("who", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                String who = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "who");
                for (var e : AotRpg.PROFILES.account(p.getUuid()).friends.entrySet()) {
                    if (e.getValue().equalsIgnoreCase(who) || e.getKey().equals(who)) {
                        AotRpg.SOCIAL.action(p, "friend_remove", java.util.UUID.fromString(e.getKey()));
                        return 1;
                    }
                }
                c.getSource().sendError(Text.literal("Not on your friends list."));
                return 0;
            }))));
        d.register(CommandManager.literal("wallet").executes(c -> {
            ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
            p.sendMessage(Text.literal("Wallet: ").formatted(Formatting.GRAY).append(Wallet.marks(AotRpg.WALLET.marks(p)))
                .append(Text.literal("  ·  ").formatted(Formatting.DARK_GRAY)).append(Wallet.gold(AotRpg.WALLET.gold(p))), false);
            return 1;
        }));
        d.register(CommandManager.literal("pay").then(CommandManager.argument("player", EntityArgumentType.player())
            .then(CommandManager.argument("marks", com.mojang.brigadier.arguments.LongArgumentType.longArg(1)).executes(c -> {
                ServerPlayerEntity from = c.getSource().getPlayerOrThrow();
                ServerPlayerEntity to = EntityArgumentType.getPlayer(c, "player");
                long n = com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "marks");
                if (to == from || !AotRpg.PROFILES.get(to.getUuid()).created) {
                    c.getSource().sendError(Text.literal("You can't pay them."));
                    return 0;
                }
                if (!AotRpg.WALLET.spendMarks(from, n)) {
                    c.getSource().sendError(Text.literal("You don't have that many Marks."));
                    return 0;
                }
                AotRpg.WALLET.addMarks(to, n, "from " + AotRpg.PROFILES.get(from.getUuid()).name);
                from.sendMessage(Text.literal("Paid ").formatted(Formatting.GRAY).append(Wallet.marks(n))
                    .append(Text.literal(" to " + AotRpg.PROFILES.get(to.getUuid()).name).formatted(Formatting.GRAY)), false);
                return 1;
            }))));
        d.register(CommandManager.literal("rp")
            .executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                Profile pr = AotRpg.PROFILES.get(p.getUuid());
                Roles.Rank r = Roles.rank(pr);
                p.sendMessage(Text.literal("Roleplay " + (pr.rp ? "on" : "off") + " · " + r.title() + " (" + pr.rpPoints + " points) · bonus +"
                    + Math.round(Roles.bonus(pr) * 100) + "% XP and Marks").formatted(Formatting.GOLD), false);
                return 1;
            })
            .then(CommandManager.literal("on").executes(c -> {
                AotRpg.ROLES.toggle(c.getSource().getPlayerOrThrow(), true);
                return 1;
            }))
            .then(CommandManager.literal("off").executes(c -> {
                AotRpg.ROLES.toggle(c.getSource().getPlayerOrThrow(), false);
                return 1;
            })));
        d.register(CommandManager.literal("aotrpg").requires(s -> s.hasPermissionLevel(2)).then(CommandManager.literal("role")
            .then(CommandManager.literal("set").then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("color", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .then(CommandManager.argument("title", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c -> {
                        ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                        String col = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "color").replace("#", "");
                        int rgb;
                        try {
                            rgb = Integer.parseInt(col, 16);
                        } catch (NumberFormatException e) {
                            c.getSource().sendError(Text.literal("Colour must be hex, e.g. FFD24A"));
                            return 0;
                        }
                        String title = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "title");
                        AotRpg.ROLES.setRole(p, title, rgb);
                        c.getSource().sendFeedback(() -> Text.literal(p.getName().getString() + " is now: " + title), true);
                        return 1;
                    })))))
            .then(CommandManager.literal("clear").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
                ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                AotRpg.ROLES.setRole(p, "", 0);
                c.getSource().sendFeedback(() -> Text.literal("Cleared " + p.getName().getString() + "'s role."), true);
                return 1;
            })))
            .then(CommandManager.literal("points").then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("amount", IntegerArgumentType.integer(0)).executes(c -> {
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                    Profile pr = AotRpg.PROFILES.get(p.getUuid());
                    pr.rpPoints = IntegerArgumentType.getInteger(c, "amount");
                    AotRpg.PROFILES.save(p.getUuid());
                    AotRpg.ROLES.refresh(p);
                    c.getSource().sendFeedback(() -> Text.literal(p.getName().getString() + " is now " + Roles.rank(pr).title()), true);
                    return 1;
                }))))));
        d.register(CommandManager.literal("home").executes(c -> {
            AotRpg.HOMES.command(c.getSource().getPlayerOrThrow());
            return 1;
        }).then(CommandManager.literal("offers").executes(c -> {
            AotRpg.HOMES.action(c.getSource().getPlayerOrThrow(), "offers", -1, "");
            return 1;
        })).then(CommandManager.literal("admin").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
            HomeAdmin.send(c.getSource().getPlayerOrThrow(), "");
            return 1;
        })).then(CommandManager.literal("manage").executes(c -> {
            AotRpg.HOMES.action(c.getSource().getPlayerOrThrow(), "manage", -1, "");
            return 1;
        })));
        d.register(CommandManager.literal("market").executes(c -> {
            AotRpg.MARKET.open(c.getSource().getPlayerOrThrow());
            return 1;
        }));
        d.register(CommandManager.literal("faction").executes(c -> {
            AotRpg.FACTIONS.send(c.getSource().getPlayerOrThrow(), true);
            return 1;
        }));
        d.register(CommandManager.literal("characters").executes(c -> {
            AotRpg.CHARACTERS.send(c.getSource().getPlayerOrThrow(), true);
            return 1;
        }));
        for (String cur : new String[] {"marks", "gold"}) {
            boolean gold = cur.equals("gold");
            d.register(CommandManager.literal("aotrpg").requires(s -> s.hasPermissionLevel(2)).then(CommandManager.literal(cur)
                .then(money("give", gold, 1)).then(money("take", gold, -1)).then(money("set", gold, 0))));
        }
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> money(String verb, boolean gold, int sign) {
        return CommandManager.literal(verb).then(CommandManager.argument("player", EntityArgumentType.player())
            .then(CommandManager.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(0)).executes(c -> {
                ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
                long n = com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "amount");
                long now = gold ? AotRpg.WALLET.gold(p) : AotRpg.WALLET.marks(p);
                long delta = sign == 0 ? n - now : sign * n;
                if (gold) AotRpg.WALLET.addGold(p, delta);
                else AotRpg.WALLET.addMarks(p, delta, null);
                long after = gold ? AotRpg.WALLET.gold(p) : AotRpg.WALLET.marks(p);
                c.getSource().sendFeedback(() -> Text.literal(p.getName().getString() + " now has ")
                    .append(gold ? Wallet.gold(after) : Wallet.marks(after)), true);
                return 1;
            })));
    }

    private static int protect(ServerCommandSource src, boolean on) {
        AotRpg.CARE.config.protect = on;
        AotRpg.CARE.saveConfig();
        src.sendFeedback(() -> Text.literal(on ? "The land is protected: players cannot break or place blocks outside build zones."
            : "Protection is OFF: everyone can build and break."), true);
        return 1;
    }

    private static int regen(ServerCommandSource src, boolean on) {
        AotRpg.CARE.config.regen = on;
        AotRpg.CARE.saveConfig();
        src.sendFeedback(() -> Text.literal(on ? "Destroyed blocks regenerate over time." : "Regeneration is OFF (waiting blocks are kept)."), true);
        return 1;
    }

    private static int cosmetic(ServerCommandSource src, ServerPlayerEntity p, String id, boolean on) {
        if (!id.equals("all") && Cosmetics.def(id) == null) {
            src.sendError(Text.literal("Unknown cosmetic: " + id));
            return 0;
        }
        AotRpg.COSMETICS.grant(p, id, on);
        src.sendFeedback(() -> Text.literal((on ? "Granted " : "Revoked ") + id + (on ? " to " : " from ") + p.getName().getString()), true);
        return 1;
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
