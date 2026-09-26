package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Discipline;
import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/** Party frames on the left side of the screen. */
public final class PartyHud {
    private PartyHud() {}

    private static final int W = 128, H = 30;
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<Net.PartyMember> party = ClientState.party;
        ClientPlayerEntity me = mc.player;
        if (party.isEmpty() || me == null || mc.currentScreen != null || mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;

        int x = 4;
        // Below the minimap and objective.
        int y = Math.max(4, Minimap.bottom);

        c.drawTextWithShadow(Ui.font(), Ui.heading("Party  " + (party.size() + 1) + "/6"), x + 2, y, Ui.GOLD);
        y += 12;
        for (Net.PartyMember m : party) {
            frame(c, me, m, x, y);
            y += H + 3;
        }
    }

    private static void frame(DrawContext c, ClientPlayerEntity me, Net.PartyMember m, int x, int y) {
        boolean on = m.online();
        c.fill(x, y, x + W, y + H, on ? 0xC80D110E : 0x900D0D0D);
        c.drawBorder(x, y, W, H, m.leader() ? Ui.GOLD : Ui.BORDER);
        com.pglol.aotrpg.PlayerClass role = m.discipline() >= 0 && m.discipline() < com.pglol.aotrpg.PlayerClass.values().length ? com.pglol.aotrpg.PlayerClass.values()[m.discipline()] : null;
        int accent = role != null ? role.color : Ui.MUTED;
        c.fill(x + 1, y + 1, x + 3, y + H - 1, on ? accent : Ui.DIM);

        String name = (m.leader() ? "★ " : "") + m.name();
        int nameW = W - 40;
        String shown = Ui.font().trimToWidth(name, nameW);
        c.drawTextWithShadow(Ui.font(), Text.literal(shown), x + 6, y + 3, on ? (m.leader() ? Ui.GOLD : Ui.CREAM) : Ui.DIM);

        // Right side: level, or distance and direction.
        String right;
        if (!on) right = "Offline";
        else if (!m.sameWorld()) right = "Away";
        else {
            double dx = m.x() - me.getX(), dz = m.z() - me.getZ();
            int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            float target = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float rel = MathHelper.wrapDegrees(target - me.getYaw());
            int idx = Math.floorMod(Math.round(rel / 45f), 8);
            right = (dist > 9999 ? "9999+" : dist) + "m " + ARROWS[idx];
        }
        c.drawTextWithShadow(Ui.font(), Text.literal(right), x + W - 4 - Ui.font().getWidth(right), y + 3, Ui.MUTED);

        if (!on) {
            c.drawTextWithShadow(Ui.font(), Text.literal("Lv " + m.level()), x + 6, y + 15, Ui.DIM);
            return;
        }
        String sub = m.level() > 0 ? "Lv " + m.level() + " " + (role == null ? "" : role.tag() + " " + role.title) : "Creating character";
        c.drawTextWithShadow(Ui.font(), Text.literal(sub), x + 6, y + 12, accent);
        float hp = m.maxHealth() > 0 ? m.health() / m.maxHealth() : 0;
        int hpColor = hp < 0.3f ? 0xFFE0442F : Ui.HP;
        Ui.bar(c, x + 6, y + 22, W - 12, 4, hp, hpColor);
        Ui.bar(c, x + 6, y + 26, W - 12, 3, m.stamina(), Ui.STAMINA);
        if (m.health() <= 0) {
            c.drawTextWithShadow(Ui.font(), Text.literal("DOWN"), x + W - 4 - Ui.font().getWidth("DOWN"), y + 12, Ui.RED);
        }
    }
}
