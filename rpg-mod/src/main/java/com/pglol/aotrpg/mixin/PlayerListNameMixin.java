package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.AotRpg;
import com.pglol.aotrpg.Profile;
import com.pglol.aotrpg.Roles;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The player list (Tab) shows the character name with its roleplay tag. */
@Mixin(ServerPlayerEntity.class)
public abstract class PlayerListNameMixin {
    @Inject(method = "getPlayerListName", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$listName(CallbackInfoReturnable<Text> cir) {
        Profile pr = AotRpg.PROFILES.get(((ServerPlayerEntity) (Object) this).getUuid());
        if (pr.created) cir.setReturnValue(Roles.styledName(pr));
    }
}
