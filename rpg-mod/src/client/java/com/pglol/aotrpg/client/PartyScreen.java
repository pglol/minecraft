package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** The party at a glance: members, and one-click invites for players nearby. */
public final class PartyScreen extends Screen {
    private int left, top, w, h;

    public PartyScreen() {
        super(Text.literal("Party"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void cmd(String c) {
        if (client.player != null) client.player.networkHandler.sendChatCommand(c);
    }

    private boolean leader() {
        for (Net.PartyMember m : ClientState.party) if (m.leader() && client.player != null && m.id().equals(client.player.getUuid())) return true;
        return false;
    }

    private List<PlayerEntity> nearby() {
        List<PlayerEntity> out = new ArrayList<>();
        if (client.world == null || client.player == null) return out;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || ClientState.partyMember(p.getUuid()) != null) continue;
            if (p.squaredDistanceTo(client.player) < 48 * 48) out.add(p);
        }
        return out;
    }

    @Override
    protected void init() {
        w = Math.min(380, width - 20);
        left = (width - w) / 2;
        top = 60;
        h = height - top - 20;
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        int y = top + 26 + Math.max(1, ClientState.party.size()) * 24 + 30;
        for (PlayerEntity p : nearby()) {
            if (y > top + h - 50) break;
            String name = p.getName().getString();
            addDrawableChild(new AotButton(left + w - 90, y, 80, 18, Ui.heading("Invite"), () -> {
                cmd("party invite " + name);
                close();
            }));
            y += 22;
        }
        if (!ClientState.party.isEmpty()) {
            addDrawableChild(new AotButton(left + 10, top + h - 28, 100, 20, Ui.heading("Leave"), () -> {
                cmd("party leave");
                close();
            }));
            if (leader()) {
                AotButton d = new AotButton(left + 116, top + h - 28, 100, 20, Ui.heading("Disband"), () -> {
                    cmd("party disband");
                    close();
                });
                d.accent = Ui.RED;
                addDrawableChild(d);
            }
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.crest(c, width / 2 - 11, 3, 22, 0.9f);
        Ui.text(c, Ui.title("PARTY"), width / 2f, 27, 1.2f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        int y = top + 8;
        Ui.text(c, Ui.heading("Members"), left + 10, y, 1f, Ui.GOLD, false);
        y += 16;
        if (ClientState.party.isEmpty()) {
            Ui.text(c, Text.literal("You are not in a party. Invite someone nearby below."), left + 10, y + 4, 0.85f, Ui.MUTED, false);
            y += 24;
        }
        for (Net.PartyMember m : ClientState.party) {
            c.fill(left + 8, y, left + w - 8, y + 21, 0x40000000);
            Ui.text(c, Text.literal((m.leader() ? "★ " : "") + m.name()), left + 12, y + 3, 1f, m.online() ? Ui.CREAM : Ui.DIM, false);
            Ui.text(c, Text.literal("Lv " + m.level()), left + w - 50, y + 3, 0.9f, Ui.GOLD, false);
            float hp = m.maxHealth() > 0 ? Math.max(0, Math.min(1, m.health() / m.maxHealth())) : 0;
            Ui.bar(c, left + 12, y + 14, w / 2, 4, hp, Ui.HP);
            y += 24;
        }
        y += 6;
        Ui.divider(c, left + 10, y, w - 20);
        y += 8;
        Ui.text(c, Ui.heading("Nearby"), left + 10, y, 1f, Ui.GOLD, false);
        y += 18;
        List<PlayerEntity> near = nearby();
        if (near.isEmpty()) Ui.text(c, Text.literal("No one within 48 blocks."), left + 10, y + 4, 0.85f, Ui.MUTED, false);
        for (PlayerEntity p : near) {
            if (y > top + h - 50) break;
            Net.RosterEntry r = ClientState.roster.get(p.getUuid());
            Ui.text(c, Text.literal(r != null ? r.name() : p.getName().getString()), left + 12, y + 5, 1f, Ui.CREAM, false);
            if (r != null) Ui.text(c, Text.literal("Lv " + r.level()), left + w - 130, y + 5, 0.9f, Ui.GOLD, false);
            y += 22;
        }
    }
}
