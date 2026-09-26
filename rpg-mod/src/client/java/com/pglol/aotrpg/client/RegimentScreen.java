package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Regiments: browse and join, found your own, or run the one you are in. */
public final class RegimentScreen extends Screen {
    private static final int[] COLORS = {0xE0B96A, 0x3F8F4A, 0xB8473A, 0x4A78C0, 0x9A5CC8, 0x3AB0B0, 0xE08A3A, 0xD0D0D0};
    private static final int ROW = 22;
    private int left, top, w, h, scroll;
    private TextFieldWidget nameField, tagField, depositField;
    private static int color;
    private static boolean openJoin = true;

    public RegimentScreen() {
        super(Text.literal("Regiments"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        String n = nameField != null ? nameField.getText() : "", t = tagField != null ? tagField.getText() : "";
        clearAndInit();
        if (nameField != null) nameField.setText(n);
        if (tagField != null) tagField.setText(t);
    }

    private static void act(String a, String arg) {
        ClientPlayNetworking.send(new Net.RegimentAction(a, arg));
    }

    private static int argb(int rgb) {
        return 0xFF000000 | rgb;
    }

    @Override
    protected void init() {
        w = Math.min(560, width - 20);
        h = Math.min(320, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 10);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        Net.RegimentView v = ClientState.regiments;
        if (v == null) return;
        if (v.mine() == null) initBrowse(v);
        else initMine(v);
    }

    // ------------------------------------------------------------------ not in one

    private void initBrowse(Net.RegimentView v) {
        int lw = w * 3 / 5;
        int y = top + 30;
        for (Net.RegimentInfo r : v.invites()) {
            addDrawableChild(new AotButton(left + lw - 150, y + 2, 70, 18, Text.literal("Accept"), () -> act("join", r.id()))).accent = 0xFF5BD35B;
            addDrawableChild(new AotButton(left + lw - 76, y + 2, 66, 18, Text.literal("Decline"), () -> act("decline", r.id()))).accent = Ui.RED;
            y += ROW;
        }
        if (!v.invites().isEmpty()) y += 8;
        int rows = Math.max(1, (top + h - 10 - y) / ROW);
        List<Net.RegimentInfo> all = v.all();
        for (int i = scroll; i < Math.min(all.size(), scroll + rows); i++) {
            Net.RegimentInfo r = all.get(i);
            if (r.open() && r.members() < r.cap()) {
                addDrawableChild(new AotButton(left + lw - 60, y + 2, 50, 18, Text.literal("Join"), () -> act("join", r.id())));
            }
            y += ROW;
        }
        // Found one.
        int fx = left + lw + 14, fw = w - lw - 24;
        nameField = addDrawableChild(new TextFieldWidget(textRenderer, fx, top + 48, fw, 18, Text.literal("Name")));
        nameField.setMaxLength(24);
        nameField.setPlaceholder(Text.literal("Regiment name").withColor(Ui.DIM));
        tagField = addDrawableChild(new TextFieldWidget(textRenderer, fx, top + 84, 60, 18, Text.literal("Tag")));
        tagField.setMaxLength(4);
        tagField.setPlaceholder(Text.literal("TAG").withColor(Ui.DIM));
        for (int i = 0; i < COLORS.length; i++) {
            int c = i;
            AotButton b = addDrawableChild(new AotButton(fx + i * 18, top + 122, 16, 16, Text.empty(), () -> {
                color = c;
                refresh();
            }));
            b.accent = argb(COLORS[i]);
            b.selected = color == i;
        }
        addDrawableChild(new AotButton(fx, top + 146, fw, 18, Text.literal(openJoin ? "Anyone may join" : "Invite only"), () -> {
            openJoin = !openJoin;
            refresh();
        }));
        AotButton found = addDrawableChild(new AotButton(fx, top + h - 34, fw, 22,
            Ui.heading("Found · " + String.format(Locale.ROOT, "%,d", v.cost()) + " Marks"),
            () -> act("create", nameField.getText() + "|" + tagField.getText() + "|" + color + "|" + (openJoin ? "1" : "0"))));
        found.accent = Ui.GOLD;
        found.active = ClientState.profile != null && ClientState.profile.level() >= v.minLevel();
    }

    // ------------------------------------------------------------------ yours

    private boolean officer(Net.RegimentView v) {
        return v.role().equals("captain") || v.role().equals("officer");
    }

    private void initMine(Net.RegimentView v) {
        int lw = w * 3 / 5;
        int y = top + 74;
        boolean off = officer(v), cap = v.role().equals("captain");
        String me = client.player == null ? "" : client.player.getUuidAsString();
        int rows = Math.max(1, (top + h - 10 - y) / ROW);
        List<Net.RegimentMember> ms = v.members();
        for (int i = scroll; i < Math.min(ms.size(), scroll + rows); i++) {
            Net.RegimentMember m = ms.get(i);
            if (off && !m.uuid().equals(me) && !m.role().equals("captain") && (cap || m.role().equals("soldier"))) {
                int bx = left + lw - 12;
                AotButton k = addDrawableChild(new AotButton(bx - 34, y + 2, 34, 18, Text.literal("Kick"), () -> act("kick", m.uuid())));
                k.accent = Ui.RED;
                boolean isOff = m.role().equals("officer");
                if (cap || !isOff) {
                    addDrawableChild(new AotButton(bx - 34 - 58, y + 2, 56, 18, Text.literal(isOff ? "Demote" : "Promote"),
                        () -> act(isOff ? "demote" : "promote", m.uuid())));
                }
                if (cap) {
                    addDrawableChild(new AotButton(bx - 34 - 58 - 50, y + 2, 48, 18, Text.literal("Captain"), () -> act("captain", m.uuid())))
                        .setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Hand the regiment to " + m.name())));
                }
            }
            y += ROW;
        }
        // Right side: invite, treasury, settings.
        int fx = left + lw + 14, fw = w - lw - 24;
        int iy = top + 44;
        if (off) {
            int shown = 0;
            for (Net.RosterEntry r : ClientState.roster.values()) {
                if (r.id().toString().equals(me) || !r.regiment().isEmpty() || shown >= 5) continue;
                addDrawableChild(new AotButton(fx + fw - 50, iy, 50, 16, Text.literal("Invite"), () -> act("invite", r.id().toString())));
                iy += 18;
                shown++;
            }
            if (shown == 0) iy += 12;
        }
        int dy = top + h - 104;
        depositField = addDrawableChild(new TextFieldWidget(textRenderer, fx, dy, fw - 70, 18, Text.literal("Marks")));
        depositField.setTextPredicate(t -> t.matches("\\d{0,8}"));
        depositField.setPlaceholder(Text.literal("Marks").withColor(Ui.DIM));
        addDrawableChild(new AotButton(fx + fw - 66, dy, 66, 18, Text.literal("Donate"), () -> {
            if (!depositField.getText().isEmpty()) act("deposit", depositField.getText());
        }));
        if (off) {
            addDrawableChild(new AotButton(fx, dy + 24, fw, 18, Text.literal(v.mine().open() ? "Open to all (click: invite only)" : "Invite only (click: open)"),
                () -> act("toggle_open", "")));
        }
        AotButton leave = addDrawableChild(new AotButton(fx, top + h - 32, cap ? fw / 2 - 2 : fw, 20, Text.literal("Leave"), () -> act("leave", "")));
        leave.accent = Ui.RED;
        if (cap) {
            AotButton dis = addDrawableChild(new AotButton(fx + fw / 2 + 2, top + h - 32, fw / 2 - 2, 20, Text.literal("Disband"), () -> {
                if (hasShiftDown()) act("disband", "");
            }));
            dis.accent = Ui.RED;
            dis.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Shift-click to disband for good")));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        Net.RegimentView v = ClientState.regiments;
        if (v == null) return false;
        int size = v.mine() == null ? v.all().size() : v.members().size();
        scroll = Math.max(0, Math.min(Math.max(0, size - 6), scroll - (int) Math.signum(vy)));
        refresh();
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("REGIMENTS"), width / 2f, top - 40, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.RegimentView v = ClientState.regiments;
        if (v == null) return;
        int lw = w * 3 / 5;
        c.fill(left + lw, top + 8, left + lw + 1, top + h - 8, 0x607A6139);
        if (v.mine() == null) drawBrowse(c, v, lw);
        else drawMine(c, v, lw);
    }

    private void drawBrowse(DrawContext c, Net.RegimentView v, int lw) {
        Ui.text(c, Ui.heading("Regiments"), left + 10, top + 8, 1f, Ui.GOLD, false);
        Ui.text(c, Text.literal("Squads players founded. Levels raise the member cap and give +1% XP and Marks each."), left + 10, top + 20, 0.6f, Ui.MUTED, false);
        int y = top + 30;
        for (Net.RegimentInfo r : v.invites()) {
            c.fill(left + 6, y, left + lw - 6, y + ROW, 0x405BD35B);
            Ui.text(c, Text.literal("Invite: [" + r.tag() + "] " + r.name()), left + 10, y + 7, 0.8f, argb(r.color()), false);
            y += ROW;
        }
        if (!v.invites().isEmpty()) y += 8;
        if (v.all().isEmpty()) Ui.text(c, Text.literal("No regiments yet. Found the first!"), left + 12, y + 6, 0.8f, Ui.MUTED, false);
        int rows = Math.max(1, (top + h - 10 - y) / ROW);
        for (int i = scroll; i < Math.min(v.all().size(), scroll + rows); i++) {
            Net.RegimentInfo r = v.all().get(i);
            if ((i & 1) == 0) c.fill(left + 6, y, left + lw - 6, y + ROW, 0x22000000);
            c.fill(left + 6, y, left + 8, y + ROW, argb(r.color()));
            Ui.text(c, Text.literal("[" + r.tag() + "] ").withColor(argb(r.color())).append(Text.literal(r.name()).withColor(Ui.CREAM)),
                left + 12, y + 3, 0.85f, Ui.CREAM, false);
            Ui.text(c, Text.literal("Lv " + r.level() + " · " + r.members() + "/" + r.cap() + " · Capt. " + r.captain()
                + (r.open() ? "" : " · invite only")), left + 12, y + 13, 0.58f, Ui.MUTED, false);
            y += ROW;
        }
        int fx = left + lw + 14;
        Ui.text(c, Ui.heading("Found a regiment"), fx, top + 8, 1f, Ui.GOLD, false);
        Ui.text(c, Text.literal("Name"), fx, top + 38, 0.65f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Tag (2-4, shown on name plates)"), fx, top + 74, 0.65f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Colour"), fx, top + 112, 0.65f, Ui.MUTED, false);
        Net.Sync p = ClientState.profile;
        String need = "Level " + v.minLevel() + "+ · " + String.format(Locale.ROOT, "%,d", v.cost()) + " Marks";
        Ui.text(c, Text.literal(need), fx, top + h - 46, 0.65f, p != null && p.level() >= v.minLevel() ? Ui.MUTED : Ui.RED, false);
        // Preview of the tag.
        String t = tagField == null ? "" : tagField.getText().toUpperCase(Locale.ROOT);
        if (!t.isEmpty()) Ui.text(c, Text.literal("[" + t + "] " + (p == null ? "" : p.name())), fx + 68, top + 89, 0.75f, argb(COLORS[color]), false);
    }

    private void drawMine(DrawContext c, Net.RegimentView v, int lw) {
        Net.RegimentInfo r = v.mine();
        int col = argb(r.color());
        c.fill(left + 6, top + 6, left + 9, top + 58, col);
        Ui.text(c, Text.literal("[" + r.tag() + "] ").withColor(col).append(Ui.heading(r.name()).withColor(Ui.CREAM)), left + 14, top + 8, 1.1f, Ui.CREAM, false);
        Ui.text(c, Text.literal("Level " + r.level() + " · " + r.members() + "/" + r.cap() + " members · +" + r.level() + "% XP and Marks · you: "
            + v.role()), left + 14, top + 24, 0.65f, Ui.MUTED, false);
        float f = r.xpNext() > r.xpLevel() ? (float) (r.xp() - r.xpLevel()) / (r.xpNext() - r.xpLevel()) : 1;
        Ui.bar(c, left + 14, top + 36, lw - 30, 5, Math.max(0, Math.min(1, f)), Ui.XP);
        Ui.text(c, Text.literal(String.format(Locale.ROOT, "%,d / %,d regiment XP", r.xp(), r.xpNext())), left + 14, top + 44, 0.58f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Kills, waves, raids and donations earn regiment XP."), left + 14, top + 53, 0.55f, Ui.DIM, false);
        int y = top + 74;
        Ui.text(c, Ui.heading("Members"), left + 10, y - 10, 0.8f, Ui.GOLD, false);
        int rows = Math.max(1, (top + h - 10 - y) / ROW);
        List<Net.RegimentMember> ms = new ArrayList<>(v.members());
        for (int i = scroll; i < Math.min(ms.size(), scroll + rows); i++) {
            Net.RegimentMember m = ms.get(i);
            if ((i & 1) == 0) c.fill(left + 6, y, left + lw - 6, y + ROW, 0x22000000);
            c.fill(left + 10, y + 8, left + 14, y + 12, m.online() ? 0xFF5BD35B : 0xFF55524A);
            int rc = m.role().equals("captain") ? Ui.GOLD : m.role().equals("officer") ? 0xFF7FB0FF : Ui.CREAM;
            Ui.text(c, Text.literal(m.name()), left + 20, y + 3, 0.8f, rc, false);
            Ui.text(c, Text.literal(m.role() + (m.online() ? " · Lv " + m.level() : " · offline") + " · " + String.format(Locale.ROOT, "%,d", m.contrib()) + " XP"),
                left + 20, y + 13, 0.55f, Ui.MUTED, false);
            y += ROW;
        }
        int fx = left + lw + 14, fw = w - lw - 24;
        Ui.text(c, Ui.heading("Treasury"), fx, top + 8, 0.9f, Ui.GOLD, false);
        Ui.text(c, Text.literal(String.format(Locale.ROOT, "%,d Marks", r.treasury())), fx, top + 20, 0.9f, Ui.CREAM, false);
        if (officer(v)) {
            Ui.text(c, Ui.heading("Invite (online)"), fx, top + 34, 0.7f, Ui.GOLD, false);
            int iy = top + 44, shown = 0;
            String me = client.player == null ? "" : client.player.getUuidAsString();
            for (Net.RosterEntry e : ClientState.roster.values()) {
                if (e.id().toString().equals(me) || !e.regiment().isEmpty() || shown >= 5) continue;
                Ui.text(c, Text.literal(e.name() + "  Lv " + e.level()), fx, iy + 4, 0.7f, Ui.CREAM, false);
                iy += 18;
                shown++;
            }
            if (shown == 0) Ui.text(c, Text.literal("Nobody free to invite"), fx, iy + 2, 0.6f, Ui.DIM, false);
        }
        Ui.text(c, Text.literal("Donate to the treasury (1 regiment XP per 50 Marks)"), fx, top + h - 114, 0.55f, Ui.MUTED, false);
    }
}
