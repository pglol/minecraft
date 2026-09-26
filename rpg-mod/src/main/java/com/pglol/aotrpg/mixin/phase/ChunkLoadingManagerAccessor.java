package com.pglol.aotrpg.mixin.phase;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The world's entity trackers, by entity id. */
@Mixin(ServerChunkLoadingManager.class)
public interface ChunkLoadingManagerAccessor {
    @SuppressWarnings("rawtypes")
    @Accessor("entityTrackers")
    Int2ObjectMap aotrpg$trackers();
}
