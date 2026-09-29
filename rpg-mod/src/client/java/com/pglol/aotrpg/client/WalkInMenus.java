package com.pglol.aotrpg.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * This is a PvPvE game: rummaging through a crate or checking the map doesn't root you to the
 * spot. While the loot screen or a map is up, the movement keys (walk, strafe, jump, sneak,
 * sprint) still move you; the mouse stays on the screen.
 */
public final class WalkInMenus {
    private WalkInMenus() {}

    private static boolean was;

    private static boolean walkable(MinecraftClient mc) {
        return mc.currentScreen instanceof LootScreen || mc.currentScreen instanceof CraftScreen ||mc.currentScreen instanceof IslandMapScreen || mc.currentScreen instanceof WorldMapScreen;
    }

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.getWindow() == null) return;
        boolean now = walkable(mc);
        KeyBinding[] keys = {mc.options.forwardKey, mc.options.backKey, mc.options.leftKey, mc.options.rightKey,
            mc.options.jumpKey, mc.options.sneakKey, mc.options.sprintKey};
        if (!now) {
            if (was) for (KeyBinding k : keys) k.setPressed(false);
            was = false;
            return;
        }
        was = true;
        long handle = mc.getWindow().getHandle();
        for (KeyBinding k : keys) {
            InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(k);
            boolean down = key.getCategory() == InputUtil.Type.KEYSYM && key.getCode() != InputUtil.UNKNOWN_KEY.getCode()
                && InputUtil.isKeyPressed(handle, key.getCode());
            k.setPressed(down);
        }
    }
}
