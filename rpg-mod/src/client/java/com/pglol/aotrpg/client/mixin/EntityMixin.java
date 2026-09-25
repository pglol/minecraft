package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.ClientState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Outline colour for party members: green, gold for the leader. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void aotrpg$partyColor(CallbackInfoReturnable<Integer> cir) {
        // Dropped gear glows in its rarity colour.
        if ((Object) this instanceof net.minecraft.entity.ItemEntity item) {
            int r = com.pglol.aotrpg.client.GearUi.rarity(item.getStack());
            if (r >= 0) cir.setReturnValue(com.pglol.aotrpg.client.GearUi.color(r));
            return;
        }
        if (ClientState.party.isEmpty()) return;
        Net.PartyMember m = ClientState.partyMember(((Entity) (Object) this).getUuid());
        if (m != null) cir.setReturnValue(m.leader() ? 0xF2C14E : 0x5BD35B);
    }
}
