package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import com.pglol.aotrpg.SatchelHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Client side: creator and character screens, the RPG HUD, the K key. */
public final class AotRpgClient implements ClientModInitializer {
    private static KeyBinding characterKey, mapKey, journalKey, satchelKey, healKey, socialKey, sheathKey;

    public static KeyBinding sheathKey() {
        return sheathKey;
    }

    public static KeyBinding healKey() {
        return healKey;
    }

    public static KeyBinding socialKey() {
        return socialKey;
    }

    public static KeyBinding mapKey() {
        return mapKey;
    }

    public static KeyBinding journalKey() {
        return journalKey;
    }

    @Override
    public void onInitializeClient() {
        characterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.character",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.aot_rpg"));
        satchelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.satchel",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.aot_rpg"));
        HandledScreens.register(SatchelHandler.TYPE, SatchelScreen::new);
        mapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.map",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.aot_rpg"));
        journalKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.journal",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.aot_rpg"));
        healKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.heal",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.aot_rpg"));
        sheathKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.sheath",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.aot_rpg"));
        socialKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.aot_rpg.social",
            InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "category.aot_rpg"));
        WorldRenderEvents.AFTER_ENTITIES.register(Beams::render);
        ClientPlayNetworking.registerGlobalReceiver(Net.HealInfo.ID, (payload, ctx) -> {
            CombatHotbar.heal = payload;
            CombatHotbar.healAt = net.minecraft.util.Util.getMeasuringTimeMs();
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.CosmeticsSync.ID, (payload, ctx) -> {
            ClientState.cosmetics = payload.unlocked();
            ClientState.trail = payload.selected().isEmpty() ? "trail_tracer" : payload.selected().get(0);
            ClientState.cosmeticsAll = payload.allowlisted();
            if (ctx.client().currentScreen instanceof CosmeticsScreen s) s.refresh();
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.SheathState.ID, (payload, ctx) -> ClientState.sheaths.put(payload.player(), payload));
        net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof net.minecraft.client.render.entity.PlayerEntityRenderer pr) helper.register(new SheathFeature(pr));
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.Trail.ID, (payload, ctx) -> Trails.spawn(payload));

        ClientPlayNetworking.registerGlobalReceiver(Net.OpenCreator.ID, (payload, ctx) -> {
            // A fresh creator (not a rejected attempt) means the character was reset.
            if (payload.error().isEmpty()) {
                ClientState.resetCharacter();
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
        ClientPlayNetworking.registerGlobalReceiver(Net.Areas.ID, (payload, ctx) -> ClientState.areas = payload.areas());
        ClientPlayNetworking.registerGlobalReceiver(Net.MapFiles.ID, (payload, ctx) -> MapData.onFiles(payload));
        ClientPlayNetworking.registerGlobalReceiver(Net.MapChunk.ID, (payload, ctx) -> MapData.onChunk(payload));
        ClientPlayNetworking.registerGlobalReceiver(Net.Markers.ID, (payload, ctx) -> ClientState.markers = payload.markers());
        ClientPlayNetworking.registerGlobalReceiver(Net.Quests.ID, (payload, ctx) -> {
            ClientState.quests = payload.quests();
            if (ctx.client().currentScreen instanceof JournalScreen j) j.refresh();
            if (ctx.client().currentScreen instanceof WorldMapScreen m) m.refresh();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientState.reset();
            ClientState.sheaths.clear();
            MapData.reset();
        });

        HudRenderCallback.EVENT.register(RpgHud::render);
        HudRenderCallback.EVENT.register(Minimap::render);
        HudRenderCallback.EVENT.register(PartyHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (characterKey.wasPressed()) {
                if (ClientState.profile != null && client.currentScreen == null) client.setScreen(new CharacterScreen(0));
            }
            // M opens the world map, Ctrl+M hides or shows the minimap.
            while (mapKey.wasPressed()) {
                if (net.minecraft.client.gui.screen.Screen.hasControlDown()) ClientState.minimap = !ClientState.minimap;
                else if (client.currentScreen == null) client.setScreen(new WorldMapScreen());
            }
            while (journalKey.wasPressed()) {
                if (client.currentScreen == null && ClientState.profile != null) client.setScreen(new JournalScreen());
            }
            while (satchelKey.wasPressed()) {
                if (ClientState.profile != null && client.currentScreen == null) ClientPlayNetworking.send(new Net.OpenSatchel());
            }
            while (healKey.wasPressed()) {
                if (ClientState.profile != null && client.currentScreen == null) ClientPlayNetworking.send(new Net.QuickHealUse());
            }
            while (sheathKey.wasPressed()) {
                if (ClientState.profile != null && client.currentScreen == null) ClientPlayNetworking.send(new Net.ToggleSheath());
            }
            while (socialKey.wasPressed()) {
                if (ClientState.profile != null && client.currentScreen == null) client.setScreen(new SocialWheel());
            }
            Trails.tickShooting(client);
            Minimap.tick(client);
            // Exhausted: no sprinting until stamina recovers.
            if (ClientState.exhausted && client.player != null && client.player.isSprinting()) client.player.setSprinting(false);
        });
    }
}
