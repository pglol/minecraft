package com.pglol.aotrpg;

import net.minecraft.server.network.ServerPlayerEntity;

/** An entity tracker that can be asked to reconsider one player (show or hide its entity for them). */
public interface PhaseTracker {
    void aotrpg$refresh(ServerPlayerEntity player);
}
