package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.AotRpg;
import com.pglol.aotrpg.Profile;
import com.pglol.aotrpg.Roles;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chat and death messages use the character name with its roleplay tag. */
@Mixin(PlayerEntity.class)
public abstract class PlayerNameMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$name(CallbackInfoReturnable<Text> cir) {
        if (!((Object) this instanceof ServerPlayerEntity sp)) return;
        Profile pr = AotRpg.PROFILES.get(sp.getUuid());
        if (pr.created) cir.setReturnValue(Roles.styledName(pr, sp.getUuid()));
    }
}
