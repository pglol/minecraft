package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.pglol.aotrpg.SatchelHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Client side: creator and character screens, the RPG HUD, the K key. */
public final class AotRpgClient implements ClientModInitializer {
    private static KeyBinding characterKey, mapKey, satchelKey;

    @Override
    public void onInitializeClient() {
        characterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.character",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.aot_rpg"));
        satchelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.satchel",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.aot_rpg"));
        HandledScreens.register(SatchelHandler.TYPE, SatchelScreen::new);
        mapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.minimap",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.aot_rpg"));

        ClientPlayNetworking.registerGlobalReceiver(Net.OpenCreator.ID, (payload, ctx) -> {
            // A fresh creator (not a rejected attempt) means the character was reset.
            if (payload.error().isEmpty()) {
                ClientState.reset();
                CreatorScreen.draft = null;
            }
            ctx.client().setScreen(new CreatorScreen(payload.error()));
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.Sync.ID, (payload, ctx) -> {
            ClientState.profile = payload;
            CreatorScreen.draft = null;
            if (ctx.client().currentScreen instanceof CharacterScreen s) s.refresh();
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.StaminaSync.ID, (payload, ctx) -> {
            ClientState.stamina = payload.stamina();
            ClientState.maxStamina = payload.max();
            ClientState.exhausted = payload.exhausted();
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.OpenCharacter.ID, (payload, ctx) -> {
            if (ClientState.profile != null) ctx.client().setScreen(new CharacterScreen(payload.tab()));
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.PartySync.ID, (payload, ctx) -> ClientState.party = payload.members());
        ClientPlayNetworking.registerGlobalReceiver(Net.Roster.ID, (payload, ctx) -> {
            java.util.Map<java.util.UUID, Net.RosterEntry> m = new java.util.HashMap<>();
            for (Net.RosterEntry e : payload.players()) m.put(e.id(), e);
            ClientState.roster = m;
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.Objective.ID, (payload, ctx) -> ClientState.objective = payload);
        ClientPlayNetworking.registerGlobalReceiver(Net.Campfires.ID, (payload, ctx) -> ClientState.campfires = payload.xyz());
        ClientPlayNetworking.registerGlobalReceiver(Net.DeathInfo.ID, (payload, ctx) -> ClientState.death = payload);
        ClientPlayNetworking.registerGlobalReceiver(Net.CookingState.ID, (payload, ctx) -> {
            if (ctx.client().currentScreen instanceof CookingScreen s) s.update(payload.recipes());
            else if (payload.open()) ctx.client().setScreen(new CookingScreen(payload.recipes()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());

        HudRenderCallback.EVENT.register(RpgHud::render);
        HudRenderCallback.EVENT.register(Minimap::render);
        HudRenderCallback.EVENT.register(PartyHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (characterKey.wasPressed()) {
                if (ClientState.profile != null && client.currentScreen == null) client.setScreen(new CharacterScreen(0));
            }
            while (mapKey.wasPressed()) ClientState.minimap = !ClientState.minimap;
            while (satchelKey.wasPressed()) {
                if (ClientState.profile != null && client.currentScreen == null) ClientPlayNetworking.send(new Net.OpenSatchel());
            }
            Minimap.tick(client);
            // Exhausted: no sprinting until stamina recovers.
            if (ClientState.exhausted && client.player != null && client.player.isSprinting()) client.player.setSprinting(false);
        });
    }
}
