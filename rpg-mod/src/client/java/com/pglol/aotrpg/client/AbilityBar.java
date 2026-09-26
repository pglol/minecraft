package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.PlayerClass;
import com.pglol.aotrpg.Skill;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Your role's abilities on Z, X and V: three slots at the bottom right with their cooldowns, the
 * ultimate's charge, and a note when you're fighting alone (Lone Wolf) or in an event.
 */
public final class AbilityBar {
    private AbilityBar() {}

    public static KeyBinding[] keys = new KeyBinding[3];
    public static Net.ClassHud hud;
    private static long gotAt;

    public static void register() {
        int[] def = {GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_V};
        String[] ids = {"key.aot_rpg.ability1", "key.aot_rpg.ability2", "key.aot_rpg.ultimate"};
        for (int i = 0; i < 3; i++) {
            keys[i] = KeyBindingHelper.registerKeyBinding(new KeyBinding(ids[i], InputUtil.Type.KEYSYM, def[i], "category.aot_rpg"));
        }
    }

    public static void onHud(Net.ClassHud h) {
        hud = h;
        gotAt = Util.getMeasuringTimeMs();
    }

    public static void tick(MinecraftClient mc) {
        for (int i = 0; i < 3; i++) {
            while (keys[i].wasPressed()) {
                if (mc.currentScreen == null && ClientState.profile != null && !DownedFx.meDown()) ClientPlayNetworking.send(new Net.UseAbility(i));
            }
        }
    }

    public static boolean alone() {
        return hud != null && hud.alone();
    }

    private static Skill skill(PlayerClass c, int slot) {
        Skill.Branch b = Skill.Branch.of(c);
        for (Skill s : Skill.values()) if (s.branch == b && s.slot() == slot) return s;
        return null;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.player == null || hud == null || ClientState.profile == null || mc.currentScreen != null) return;
        PlayerClass role = PlayerClass.values()[Math.max(0, Math.min(3, hud.cls()))];
        long now = Util.getMeasuringTimeMs(), since = now - gotAt;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int S = 24, gap = 4;
        int x0 = w - 3 * (S + gap) - 6, y0 = h - S - 22;
        // Role and state above the slots.
        Text head = Text.literal(role.tag() + " " + role.title).withColor(role.color);
        Ui.text(c, Ui.heading(head.getString()).withColor(role.color), x0, y0 - 11, 0.75f, role.color, false);
        String note = hud.event() ? "EVENT · ult ×2" : hud.alone() ? "LONE WOLF" : "";
        if (!note.isEmpty()) {
            int col = hud.event() ? 0xFFFFB040 : 0xFF9AB8D8;
            Ui.text(c, Text.literal(note), w - 6 - Ui.font().getWidth(note) * 0.65f, y0 - 10, 0.65f, col, false);
        }
        for (int i = 0; i < 3; i++) {
            int x = x0 + i * (S + gap), y = y0;
            boolean learned = (hud.mask() & (1 << i)) != 0;
            Skill sk = skill(role, i);
            c.fill(x, y, x + S, y + S, 0xD0101310);
            if (sk != null) c.drawItem(new ItemStack(sk.icon), x + 4, y + 4);
            if (!learned) {
                c.fill(x + 1, y + 1, x + S - 1, y + S - 1, 0xC0000000);
                c.drawBorder(x, y, S, S, 0xFF3A3830);
            } else if (i < 2) {
                long left = Math.max(0, hud.cdLeft()[i] - since), max = Math.max(1, hud.cdMax()[i]);
                if (left > 0) {
                    int hh = (int) Math.ceil((S - 2) * left / (double) max);
                    c.fill(x + 1, y + 1, x + S - 1, y + 1 + hh, 0xB0000000);
                    String t = left >= 10_000 ? String.valueOf(left / 1000) : String.format(java.util.Locale.ROOT, "%.1f", left / 1000.0);
                    Ui.text(c, Text.literal(t), x + S / 2f, y + 8, 0.8f, 0xFFEDE3C8, true);
                    c.drawBorder(x, y, S, S, 0xFF4A4436);
                } else {
                    c.drawBorder(x, y, S, S, role.color);
                }
            } else {
                long ultLeft = Math.max(0, hud.ultLeft() - since);
                float ch = hud.charge();
                if (ultLeft > 0) {
                    int a = (int) (160 + 90 * Math.sin(now / 90.0));
                    c.drawBorder(x - 1, y - 1, S + 2, S + 2, a << 24 | (role.color & 0xFFFFFF));
                    Ui.text(c, Text.literal((ultLeft / 1000 + 1) + "s"), x + S / 2f, y + 8, 0.8f, 0xFFFFE7A0, true);
                } else if (ch >= 1) {
                    int a = (int) (150 + 100 * Math.sin(now / 160.0));
                    c.drawBorder(x - 2, y - 2, S + 4, S + 4, a << 24 | 0xFFD76A);
                    c.drawBorder(x, y, S, S, 0xFFFFD76A);
                } else {
                    int hh = Math.round((S - 2) * ch);
                    c.fill(x + 1, y + 1, x + S - 1, y + S - 1 - hh, 0xA0000000);
                    c.fill(x + 1, y + S - 1 - hh, x + S - 1, y + S - 1, 0x40FFD76A);
                    c.drawBorder(x, y, S, S, 0xFF6A5A30);
                    Ui.text(c, Text.literal(Math.round(ch * 100) + "%"), x + S / 2f, y + 16, 0.55f, 0xFFD8CFC0, true);
                }
            }
            // Key label.
            String key = keys[i].getBoundKeyLocalizedText().getString();
            if (key.length() > 3) key = key.substring(0, 3);
            Ui.text(c, Text.literal(key), x + S / 2f, y + S + 2, 0.6f, learned ? 0xFFD8CFC0 : 0xFF6A6458, true);
        }
    }
}
