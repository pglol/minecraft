package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The social hub: everyone online and your friends, with invite, friend, whisper and pay, plus
 * shortcuts to the party, battle pass, event shop, market and factions.
 */
public final class SocialScreen extends Screen {
    private static final int ROW = 26;
    private int left, top, w, h, tab, scroll;

    public SocialScreen() {
        super(Text.literal("Social"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private List<Net.SocialPlayer> rows() {
        List<Net.SocialPlayer> out = new ArrayList<>();
        Net.SocialView v = ClientState.social;
        if (v == null) return out;
        for (Net.SocialPlayer p : v.players()) {
            if (tab == 0 && p.online()) out.add(p);
            if (tab == 1 && p.friend()) out.add(p);
        }
        out.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private int visible() {
        return Math.max(1, (h - 70) / ROW);
    }

    @Override
    protected void init() {
        w = Math.min(560, width - 20);
        h = Math.min(320, height - 70);
        left = (width - w) / 2;
        top = Math.max(50, (height - h) / 2);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        String[] tabs = {"Online", "Friends"};
        for (int i = 0; i < tabs.length; i++) {
            int t = i;
            addDrawableChild(new AotButton(left + i * 104, top - 22, 100, 20, Ui.heading(tabs[i]), () -> {
                tab = t;
                scroll = 0;
                clearAndInit();
            })).selected(tab == i);
        }
        // Shortcuts along the bottom.
        String[] names = {"Party", "Tasks", "Battle Pass", "Event Shop", "Global Market", "Factions"};
        Runnable[] runs = {
            () -> client.setScreen(new PartyScreen()),
            () -> ClientPlayNetworking.send(new Net.TaskAction("open", "")),
            () -> ClientPlayNetworking.send(new Net.PassAction("open", 0)),
            () -> ClientPlayNetworking.send(new Net.EventAction("open", "")),
            () -> {
                client.setScreen(new MarketScreen(true));
                ClientPlayNetworking.send(new Net.MarketAction("exchange", "", 0, 0));
            },
            () -> ClientPlayNetworking.send(new Net.FactionAction("open", ""))};
        int bw = (w - 20 - 4 * (names.length - 1)) / names.length;
        for (int i = 0; i < names.length; i++) {
            addDrawableChild(new AotButton(left + 10 + i * (bw + 4), top + h - 28, bw, 20, Ui.heading(names[i]), runs[i]));
        }
        List<Net.SocialPlayer> rows = rows();
        scroll = Math.max(0, Math.min(scroll, rows.size() - visible()));
        int y = top + 34;
        for (int i = scroll; i < rows.size() && i < scroll + visible(); i++) {
            Net.SocialPlayer p = rows.get(i);
            int x = left + w - 10;
            if (p.online() && !p.account().isEmpty()) {
                x -= 44;
                addDrawableChild(new AotButton(x, y + 3, 42, 18, Text.literal("Pay"), () -> chat("/pay " + p.account() + " ")));
                x -= 50;
                addDrawableChild(new AotButton(x, y + 3, 48, 18, Text.literal("Whisper"), () -> chat("/msg " + p.account() + " ")));
                x -= 50;
                addDrawableChild(new AotButton(x, y + 3, 48, 18, Text.literal(p.party() ? "In party" : "Invite"),
                    () -> ClientPlayNetworking.send(new Net.SocialAction("invite", p.uuid())))).active = !p.party();
            }
            x -= 58;
            addDrawableChild(new AotButton(x, y + 3, 56, 18, Text.literal(p.friend() ? "Unfriend" : "+ Friend"),
                () -> ClientPlayNetworking.send(new Net.SocialAction(p.friend() ? "friend_remove" : "friend_add", p.uuid()))));
            y += ROW;
        }
    }

    private void chat(String text) {
        client.setScreen(new ChatScreen(text));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        scroll -= (int) Math.signum(vAmount);
        clearAndInit();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("SOCIAL"), width / 2f, top - 44, 1.4f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        List<Net.SocialPlayer> rows = rows();
        long online = ClientState.social == null ? 0 : ClientState.social.players().stream().filter(Net.SocialPlayer::online).count();
        Ui.text(c, Text.literal(tab == 0 ? online + " others online" : rows.size() + " friends"), left + 10, top + 12, 0.8f, Ui.MUTED, false);
        if (rows.isEmpty()) {
            Ui.text(c, Text.literal(tab == 0 ? "Nobody else is online." : "No friends yet: add someone from the Online tab."),
                left + w / 2f, top + 70, 0.8f, Ui.DIM, true);
        }
        int y = top + 34;
        for (int i = scroll; i < rows.size() && i < scroll + visible(); i++) {
            Net.SocialPlayer p = rows.get(i);
            c.fill(left + 6, y, left + w - 6, y + ROW - 2, p.party() ? 0x303F8F4A : 0x28000000);
            c.fill(left + 10, y + 9, left + 16, y + 15, p.online() ? 0xFF6FCF5A : 0xFF55524A);
            float x = left + 22;
            if (!p.tag().isEmpty()) {
                Text tag = Text.literal("[" + p.tag() + "]");
                Ui.text(c, tag, x, y + 4, 0.75f, 0xFF000000 | p.tagColor(), false);
                x += Ui.font().getWidth(tag) * 0.75f + 4;
            }
            Ui.text(c, Ui.heading(p.name()), x, y + 3, 0.9f, p.online() ? Ui.CREAM : Ui.DIM, false);
            String sub = p.online() ? "Lv " + p.level() + "  ·  " + p.where() : p.where();
            Ui.text(c, Text.literal(sub), left + 22, y + 14, 0.6f, Ui.MUTED, false);
            if (!p.faction().isEmpty()) {
                float fx = left + 22 + Ui.font().getWidth(sub) * 0.6f + 8;
                Ui.text(c, Text.literal(p.faction()), fx, y + 14, 0.6f, 0xFF000000 | p.factionColor(), false);
            }
            y += ROW;
        }
    }
}
