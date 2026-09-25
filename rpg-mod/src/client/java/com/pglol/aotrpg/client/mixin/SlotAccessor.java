package com.pglol.aotrpg.client.mixin;

import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the RPG inventory lay the real hotbar and off-hand slots out as a loadout. */
@Mixin(Slot.class)
public interface SlotAccessor {
    @Mutable
    @Accessor("x")
    void aotrpg$setX(int x);

    @Mutable
    @Accessor("y")
    void aotrpg$setY(int y);
}
