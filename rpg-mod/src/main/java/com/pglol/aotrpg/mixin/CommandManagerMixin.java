package com.pglol.aotrpg.mixin;

import com.mojang.brigadier.ParseResults;
import com.pglol.aotrpg.WorldCare;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block changes made by commands (/fill, /setblock, building tools) are permanent. */
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {
    @Inject(method = "execute", at = @At("HEAD"), require = 0)
    private void aotrpg$quietStart(ParseResults<ServerCommandSource> parse, String command, CallbackInfo ci) {
        WorldCare.quiet(true);
    }

    @Inject(method = "execute", at = @At("RETURN"), require = 0)
    private void aotrpg$quietEnd(ParseResults<ServerCommandSource> parse, String command, CallbackInfo ci) {
        WorldCare.quiet(false);
    }
}
