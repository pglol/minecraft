package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Client side: creator and character screens, the RPG HUD, the K key. */
public final class AotRpgClient implements ClientModInitializer {
    private static KeyBinding characterKey;

    @Override
    public void onInitializeClient() {
        characterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.character",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.aot_rpg"));

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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());

        HudRenderCallback.EVENT.register(RpgHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (characterKey.wasPressed()) {
                if (ClientState.profile != null && client.currentScreen == null) client.setScreen(new CharacterScreen(0));
            }
            // Exhausted: no sprinting until stamina recovers.
            if (ClientState.exhausted && client.player != null && client.player.isSprinting()) client.player.setSprinting(false);
        });
    }
}
