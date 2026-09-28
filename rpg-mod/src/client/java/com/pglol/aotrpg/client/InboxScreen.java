package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Gifts waiting for you: items, rewards and crates, each with who sent it. Crates open right here. */
public final class InboxScreen extends Screen {
    public static Net.InboxView view;
    private final Screen parent;
    private int scroll;

    public InboxScreen(Screen parent) {
        super(Text.literal("Inbox"));
        this.parent = parent;
    }

    public static void on(Net.InboxView v) {
        view = v;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof InboxScreen s) s.clearAndInit();
        else if (v.open()) mc.setScreen(new InboxScreen(mc.currentScreen));
    }

    /** How many gifts are waiting (for the menu's badge). */
    public static int waiting() {
        return view == null ? 0 : view.gifts().size();
    }

    /** A gifted crate's loot table, for its reveal. */
    public static List<String> loot(String crate) {
        List<String> out = new ArrayList<>();
        if (view == null) return out;
        for (String l : view.loot()) {
            int k = l.indexOf('\u0001');
            if (k > 0 && l.substring(0, k).equals(crate)) out.add(l.substring(k + 1));
        }
        return out;
    }

    private int pw() { return Math.min(width - 24, 460); }
    private int ph() { return Math.min(height - 16, 320); }
    private int px() { return width / 2 - pw() / 2; }
    private int py() { return Math.max(8, height / 2 - ph() / 2); }
    private int cols() { return pw() >= 400 ? 2 : 1; }
    private static final int CARD_H = 52, GAP = 6;
    private int rows() { return Math.max(1, (ph() - 44) / (CARD_H + GAP)); }

    @Override
    protected void init() {
        if (view == null) ClientPlayNetworking.send(new Net.InboxAction("open", ""));
        int x = px(), y = py(), w = pw();
        addDrawableChild(new AotButton(x + w - 20, y, 20, 20, Text.literal("✕"), this::close));
        if (view == null || CrateOpening.active()) return;
        List<String> gifts = view.gifts();
        boolean any = false;
        for (String g : gifts) if (!g.split("\\|")[1].equals("crate")) any = true;
        if (any) addDrawableChild(new AotButton(x + w - 110, y, 86, 20, Text.literal("Claim all"),
            () -> ClientPlayNetworking.send(new Net.InboxAction("claim_all", ""))));
        int cols = cols(), cw = (w - 20 - GAP * (cols - 1)) / cols;
        for (int k = 0; k < rows() * cols; k++) {
            int i = scroll * cols + k;
            if (i >= gifts.size()) break;
            String[] a = gifts.get(i).split("\\|", -1);
            int cx = x + 10 + (k % cols) * (cw + GAP), cy = y + 34 + (k / cols) * (CARD_H + GAP);
            boolean crate = a[1].equals("crate");
            AotButton b = addDrawableChild(new AotButton(cx + cw - 66, cy + CARD_H - 20, 60, 16, Text.literal(crate ? "Open" : "Claim"),
                () -> ClientPlayNetworking.send(new Net.InboxAction("claim", a[0]))));
            if (crate && a[7].equals("1")) b.accent = 0xFFFF3A3A;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (view == null) return false;
        int total = (view.gifts().size() + cols() - 1) / cols();
        int s = Math.max(0, Math.min(Math.max(0, total - rows()), scroll - (int) Math.signum(vy)));
        if (s != scroll) {
            scroll = s;
            clearAndInit();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (CrateOpening.click()) {
            clearAndInit();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean wasRevealing;

    @Override
    public void tick() {
        boolean r = CrateOpening.active();
        if (r != wasRevealing) {
            wasRevealing = r;
            clearAndInit();
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        int x = px(), y = py(), w = pw();
        Ui.panel(c, x, y, w, ph());
        Ui.text(c, Ui.title("INBOX"), x + 12, y + 7, 1.4f, Ui.GOLD, false);
        if (view == null) return;
        List<String> gifts = view.gifts();
        if (gifts.isEmpty()) {
            Ui.text(c, Text.literal("Nothing waiting"), x + w / 2f, y + ph() / 2f - 4, 1f, Ui.MUTED, true);
            return;
        }
        long t = net.minecraft.util.Util.getMeasuringTimeMs();
        int cols = cols(), cw = (w - 20 - GAP * (cols - 1)) / cols;
        for (int k = 0; k < rows() * cols; k++) {
            int i = scroll * cols + k;
            if (i >= gifts.size()) break;
            String[] a = gifts.get(i).split("\\|", -1);
            int cx = x + 10 + (k % cols) * (cw + GAP), cy = y + 34 + (k / cols) * (CARD_H + GAP);
            int col = 0xFF000000;
            try {
                col |= Integer.parseInt(a[4]);
            } catch (Exception ignored) { }
            boolean jackpot = a[1].equals("crate") && a[7].equals("1");
            Ui.well(c, cx, cy, cw, CARD_H);
            c.fill(cx + 1, cy + 1, cx + 3, cy + CARD_H - 1, col);
            if (jackpot) {
                // A jackpot crate glows.
                int glow = (int) (60 + 50 * Math.sin(t / 220.0));
                c.fill(cx + 3, cy + 1, cx + cw - 1, cy + CARD_H - 1, (glow << 24) | 0x801010);
            }
            Identifier id = Identifier.tryParse(a[3]);
            ItemStack icon = id != null && Registries.ITEM.containsId(id) ? new ItemStack(Registries.ITEM.get(id)) : new ItemStack(net.minecraft.item.Items.CHEST);
            Ui.item(c, icon, cx + 8, cy + 8, 1.5f);
            String title = a[2];
            while (title.length() > 3 && Ui.font().getWidth(title) * 0.85f > cw - 44) title = title.substring(0, title.length() - 2);
            if (!title.equals(a[2])) title = title + "…";
            Ui.text(c, Text.literal(title), cx + 38, cy + 6, 0.85f, col, false);
            Ui.text(c, Text.literal("From " + a[5]), cx + 38, cy + 18, 0.7f, Ui.GOLD, false);
            if (!a[6].isEmpty()) Ui.text(c, Text.literal("“" + a[6] + "”"), cx + 38, cy + 29, 0.7f, Ui.CREAM, false);
        }
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (CrateOpening.active()) CrateOpening.render(c, width, height);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
