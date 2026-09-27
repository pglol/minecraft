package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * The server store (test run, no website link yet): this week's shop for Gold, and crates for
 * Marks or Gold. Opening a crate plays a reveal: the crate shakes, bursts in the colour of what
 * came out, and shows it.
 */
public final class StoreScreen extends Screen {
    /** The last store view from the server. */
    public static Net.StoreView view;
    /** The crate being revealed, and when it started. */
    private static Net.CrateOpened reveal;
    private static long revealAt;
    private static boolean fanfare;
    private static final int[] RARITY = {0xFFB0B0B0, 0xFF5BD35B, 0xFF5A9AE0, 0xFFB06AE0, 0xFFF2C14E};
    private static final String[] RARITY_NAME = {"Common", "Uncommon", "Rare", "Epic", "Legendary"};
    private final Screen parent;

    public StoreScreen(Screen parent) {
        super(Text.literal("Store"));
        this.parent = parent;
    }

    public static void onOpened(Net.CrateOpened o) {
        reveal = o;
        revealAt = Util.getMeasuringTimeMs();
        fanfare = false;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null) mc.player.playSound(SoundEvents.BLOCK_CHEST_OPEN, 0.8f, 0.8f);
    }

    private int panelW() { return Math.min(width - 24, 460); }
    private int panelH() { return Math.min(height - 16, 336); }
    private int left() { return width / 2 - panelW() / 2; }
    private int top() { return Math.max(8, height / 2 - panelH() / 2); }
    private int cols() { return panelW() >= 400 ? 3 : 2; }

    @Override
    protected void init() {
        if (view == null || !view.open()) ClientPlayNetworking.send(new Net.StoreAction("open", ""));
        build();
    }

    /** New data from the server: rebuild the buttons. */
    public void refresh() {
        clearChildren();
        build();
    }

    private int offersTop() { return top() + 44; }
    private int cratesTop() {
        int rows = view == null ? 2 : (view.offers().size() + cols() - 1) / cols();
        return offersTop() + rows * 48 + 18;
    }

    private void build() {
        int w = panelW(), x = left(), y = top();
        addDrawableChild(new AotButton(x + w - 20, y - 0, 20, 20, Text.literal("✕"), this::close));
        if (view == null || revealing()) return;
        int cols = cols(), gap = 6, cw = (w - 20 - gap * (cols - 1)) / cols;
        for (int i = 0; i < view.offers().size(); i++) {
            Net.StoreOffer o = view.offers().get(i);
            int cx = x + 10 + (i % cols) * (cw + gap), cy = offersTop() + (i / cols) * 48;
            AotButton b = new AotButton(cx + cw - 78, cy + 26, 72, 14,
                Text.literal(o.owned() ? "Owned" : o.price() + " Gold"), () -> ClientPlayNetworking.send(new Net.StoreAction("buy", o.id())));
            b.active = !o.owned() && ClientState.gold >= o.price();
            addDrawableChild(b);
        }
        int ct = cratesTop() + 14, ch = 64;
        for (int i = 0; i < view.crates().size(); i++) {
            Net.StoreCrate c = view.crates().get(i);
            int cx = x + 10 + (i % cols) * (cw + gap), cy = ct + (i / cols) * (ch + gap);
            int bw = (cw - 14) / 2;
            AotButton m = new AotButton(cx + 5, cy + ch - 19, bw, 14, Text.literal(fmt(c.marks()) + " M"),
                () -> ClientPlayNetworking.send(new Net.StoreAction("crate", c.id())));
            m.active = ClientState.marks >= c.marks();
            AotButton g = new AotButton(cx + 9 + bw, cy + ch - 19, bw, 14, Text.literal(c.gold() + " Gold"),
                () -> ClientPlayNetworking.send(new Net.StoreAction("crate_gold", c.id())));
            g.active = ClientState.gold >= c.gold();
            addDrawableChild(m);
            addDrawableChild(g);
        }
    }

    private static String fmt(long n) {
        return n >= 1000 && n % 1000 == 0 ? (n / 1000) + "k" : n >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
    }

    private static boolean revealing() {
        return reveal != null && Util.getMeasuringTimeMs() - revealAt < 3200;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (revealing() && Util.getMeasuringTimeMs() - revealAt > 900) {
            // Click to skip the rest of the reveal.
            revealAt = Util.getMeasuringTimeMs() - 3200;
            refresh();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void close() {
        view = null;
        reveal = null;
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }

    private long lastGold = -1, lastMarks = -1;
    private boolean wasRevealing;

    @Override
    public void tick() {
        // Wallet changes (a buy, a crate) re-enable or grey out the buttons; the reveal hides them.
        boolean r = revealing();
        if (lastGold != ClientState.gold || lastMarks != ClientState.marks || r != wasRevealing) {
            lastGold = ClientState.gold;
            lastMarks = ClientState.marks;
            wasRevealing = r;
            refresh();
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        int w = panelW(), x = left(), y = top();
        Ui.panel(c, x, y, w, panelH());
        Ui.text(c, Ui.title("STORE"), x + 12, y + 8, 1.4f, Ui.GOLD, false);
        String purse = String.format(java.util.Locale.ROOT, "%,d Gold   %,d Marks", ClientState.gold, ClientState.marks);
        Ui.text(c, Text.literal(purse), x + w - 28 - Ui.font().getWidth(purse) * 0.8f, y + 8, 0.8f, Ui.GOLD, false);
        if (view == null) {
            Ui.text(c, Text.literal("Loading..."), width / 2f, y + 80, 1f, Ui.CREAM, true);
            return;
        }
        long s = view.secondsLeft();
        String left = s > 86400 ? (s / 86400) + "d " + (s % 86400 / 3600) + "h" : (s / 3600) + "h " + (s % 3600 / 60) + "m";
        Ui.text(c, Text.literal("THIS WEEK"), x + 12, y + 32, 0.8f, Ui.CREAM, false);
        String restock = "New stock in " + left;
        Ui.text(c, Text.literal(restock), x + w - 12 - Ui.font().getWidth(restock) * 0.7f, y + 33, 0.7f, Ui.MUTED, false);
        int cols = cols(), gap = 6, cw = (w - 20 - gap * (cols - 1)) / cols;
        for (int i = 0; i < view.offers().size(); i++) {
            Net.StoreOffer o = view.offers().get(i);
            int cx = x + 10 + (i % cols) * (cw + gap), cy = offersTop() + (i / cols) * 48;
            Ui.well(c, cx, cy, cw, 44);
            c.fill(cx + 1, cy + 1, cx + 3, cy + 43, 0xFF000000 | o.color());
            Ui.text(c, Text.literal(fit(o.title(), cw - 12, 0.85f)), cx + 7, cy + 5, 0.85f, 0xFF000000 | o.color(), false);
            Ui.text(c, Text.literal(o.kind()), cx + 7, cy + 16, 0.6f, Ui.MUTED, false);
        }
        int ct = cratesTop();
        Ui.text(c, Text.literal("CRATES"), x + 12, ct, 0.8f, Ui.CREAM, false);
        Ui.text(c, Text.literal("Pay in Marks or Gold"), x + 60, ct + 1, 0.6f, Ui.MUTED, false);
        ct += 14;
        float t = (Util.getMeasuringTimeMs() % 100000) / 1000f;
        for (int i = 0; i < view.crates().size(); i++) {
            Net.StoreCrate k = view.crates().get(i);
            int cx = x + 10 + (i % cols) * (cw + gap), cy = ct + (i / cols) * (64 + gap);
            Ui.well(c, cx, cy, cw, 64);
            int tone = crateTone(k.id());
            // A little crate: planks, iron bands, and a glint.
            int bx = cx + 6, by = cy + 6;
            c.fill(bx, by, bx + 16, by + 14, 0xFF6A4A2A);
            c.fill(bx, by + 4, bx + 16, by + 5, 0xFF3A2A1A);
            c.fill(bx, by + 9, bx + 16, by + 10, 0xFF3A2A1A);
            c.fill(bx + 6, by + 5, bx + 10, by + 9, tone);
            c.drawBorder(bx, by, 16, 14, 0xFF2A1A0A);
            int gx = bx + (int) ((t * 8 + i * 5) % 30) - 7;
            if (gx > bx && gx < bx + 16) c.fill(gx, by + 1, gx + 1, by + 13, 0x60FFFFFF);
            Ui.text(c, Text.literal(fit(k.title(), cw - 34, 0.85f)), cx + 27, cy + 6, 0.85f, tone, false);
            c.getMatrices().push();
            c.getMatrices().scale(0.58f, 0.58f, 1);
            c.drawTextWrapped(Ui.font(), Text.literal(k.desc()), (int) ((cx + 27) / 0.58f), (int) ((cy + 17) / 0.58f), (int) ((cw - 32) / 0.58f), Ui.MUTED);
            c.getMatrices().pop();
        }
        if (revealing()) drawReveal(c, t);
    }

    private static int crateTone(String id) {
        return switch (id) {
            case "armory" -> 0xFF5A9AE0;
            case "wardrobe" -> 0xFFB06AE0;
            case "honors" -> 0xFFE0B96A;
            case "officer" -> 0xFF5BD35B;
            case "commander" -> 0xFFF2C14E;
            default -> 0xFFB0B0B0;
        };
    }

    /** The crate reveal: it shakes, bursts, and what came out rises in its rarity's colour. */
    private void drawReveal(DrawContext c, float t) {
        long ms = Util.getMeasuringTimeMs() - revealAt;
        int cx = width / 2, cy = height / 2;
        int rar = Math.max(0, Math.min(4, reveal.rarity()));
        int col = RARITY[rar];
        c.fill(0, 0, width, height, 0xC0000000);
        if (ms < 900) {
            // Shaking, harder towards the burst.
            float k = ms / 900f;
            int dx = (int) (Math.sin(ms * 0.09) * 6 * k), dy = (int) (Math.cos(ms * 0.13) * 3 * k);
            int s = 40;
            c.fill(cx - s + dx, cy - s + dy, cx + s + dx, cy + s + dy, 0xFF6A4A2A);
            c.fill(cx - s + dx, cy - 14 + dy, cx + s + dx, cy - 10 + dy, 0xFF3A2A1A);
            c.fill(cx - s + dx, cy + 10 + dy, cx + s + dx, cy + 14 + dy, 0xFF3A2A1A);
            c.fill(cx - 8 + dx, cy - 10 + dy, cx + 8 + dx, cy + 10 + dy, col);
            c.drawBorder(cx - s + dx, cy - s + dy, s * 2, s * 2, 0xFF2A1A0A);
            // Light leaking out of the seams.
            int a = (int) (k * 200);
            c.fill(cx - s + dx, cy - 1 + dy, cx + s + dx, cy + 1 + dy, (a << 24) | (col & 0xFFFFFF));
            Ui.text(c, Text.literal(reveal.crate()), cx, cy + 56, 1f, Ui.CREAM, true);
            return;
        }
        float k = Math.min(1, (ms - 900) / 500f);
        // Rays turning behind the prize.
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8 + t * 0.6;
            int len = (int) ((60 + rar * 18) * k);
            for (int d = 16; d < len; d += 3) {
                int alpha = (int) (160 * (1 - d / (float) len));
                int px = cx + (int) (Math.cos(a) * d), py = cy + (int) (Math.sin(a) * d);
                c.fill(px - 1, py - 1, px + 1, py + 1, (alpha << 24) | (col & 0xFFFFFF));
            }
        }
        // Burst of sparks outward.
        for (int i = 0; i < 40; i++) {
            double a = i * 2.39;
            float p = Math.min(1, (ms - 900) / 1200f);
            int d = (int) (p * (80 + (i % 7) * 12));
            int alpha = (int) (255 * (1 - p));
            if (alpha > 8) c.fill(cx + (int) (Math.cos(a) * d), cy + (int) (Math.sin(a) * d), cx + (int) (Math.cos(a) * d) + 2,
                cy + (int) (Math.sin(a) * d) + 2, (alpha << 24) | (i % 3 == 0 ? 0xFFFFFF : col & 0xFFFFFF));
        }
        // The prize.
        ItemStack icon = iconStack(reveal.icon());
        c.getMatrices().push();
        float sc = 1.5f + 1.5f * k;
        c.getMatrices().translate(cx - 8 * sc, cy - 8 * sc - (1 - k) * 20, 0);
        c.getMatrices().scale(sc, sc, 1);
        c.drawItem(icon, 0, 0);
        c.getMatrices().pop();
        Ui.text(c, Text.literal(RARITY_NAME[rar].toUpperCase()), cx, cy + 34, 0.8f, col, true);
        Ui.text(c, Text.literal(reveal.got()), cx, cy + 46, 1.2f, Ui.CREAM, true);
        Ui.text(c, Text.literal("from the " + reveal.crate() + "  ·  click to continue"), cx, cy + 64, 0.65f, Ui.MUTED, true);
        if (!fanfare && client != null && client.player != null && rar >= 3) {
            fanfare = true;
            client.player.playSound(rar == 4 ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.ENTITY_PLAYER_LEVELUP, 0.7f, 1f);
        }
    }

    private static ItemStack iconStack(String id) {
        Identifier ident = Identifier.tryParse(id);
        var item = ident == null ? null : Registries.ITEM.get(ident);
        return item == null ? new ItemStack(net.minecraft.item.Items.CHEST) : new ItemStack(item);
    }

    private static String fit(String s, int w, float scale) {
        var f = Ui.font();
        if (f.getWidth(s) * scale <= w) return s;
        while (s.length() > 1 && f.getWidth(s + "...") * scale > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
