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
    private static final int[] RARITY = {0xFFB0B0B0, 0xFF5BD35B, 0xFF5A9AE0, 0xFFB06AE0, 0xFFF2C14E, 0xFFE02A2A};
    private static final String[] RARITY_NAME = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};
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
            // A colour preview: the cosmetic's two colours, or the title in its own colour on a plaque.
            CosmeticFx.Entry ce = o.id().startsWith("cosmetic:") ? CosmeticFx.entry(o.id().substring(9)) : null;
            if (ce != null) {
                for (int k = 0; k < 26; k++) {
                    float u = k / 25f;
                    int col = blend(ce.color(), ce.color2(), (float) (0.5 + 0.5 * Math.sin(u * 3 + (Util.getMeasuringTimeMs() % 100000) / 700.0)));
                    c.fill(cx + 7 + k, cy + 27, cx + 8 + k, cy + 40, 0xFF000000 | col);
                }
                c.drawBorder(cx + 6, cy + 26, 28, 15, CosmeticFx.TIER_COLORS[ce.tier()]);
            } else {
                c.fill(cx + 6, cy + 27, cx + 40, cy + 40, 0xFF1A1612);
                c.drawBorder(cx + 6, cy + 27, 34, 13, 0xFF000000 | o.color());
                Ui.text(c, Text.literal("Aa"), cx + 23, cy + 30, 0.7f, 0xFF000000 | o.color(), true);
            }
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

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (view == null || revealing()) return;
        int i = crateAt(mouseX, mouseY);
        if (i >= 0) preview(c, view.crates().get(i), mouseX, mouseY);
    }

    private int crateAt(double mx, double my) {
        if (view == null) return -1;
        int w = panelW(), x = left(), cols = cols(), gap = 6, cw = (w - 20 - gap * (cols - 1)) / cols, ct = cratesTop() + 14;
        for (int i = 0; i < view.crates().size(); i++) {
            int cx = x + 10 + (i % cols) * (cw + gap), cy = ct + (i / cols) * (64 + gap);
            // The card, above its buttons.
            if (mx >= cx && mx < cx + cw && my >= cy && my < cy + 64 - 20) return i;
        }
        return -1;
    }

    private static int blend(int a, int b, float u) {
        int r = (int) (((a >> 16) & 255) * (1 - u) + ((b >> 16) & 255) * u);
        int g = (int) (((a >> 8) & 255) * (1 - u) + ((b >> 8) & 255) * u);
        int bl = (int) ((a & 255) * (1 - u) + (b & 255) * u);
        return (r << 16) | (g << 8) | bl;
    }

    /** What's inside a crate: every roll with its rarity colour, icon, chance, and colour swatches of the cosmetics. */
    private void preview(DrawContext c, Net.StoreCrate k, int mx, int my) {
        float t = (Util.getMeasuringTimeMs() % 100000) / 1000f;
        int pw = 250, rowH = 20;
        int rows = k.loot().size();
        int ph = 34 + rows * rowH + 8;
        int px = mx + 12 + pw > width - 4 ? mx - 12 - pw : mx + 12, py = Math.max(4, Math.min(my - 10, height - ph - 4));
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 400);
        c.fill(px, py, px + pw, py + ph, 0xF0100E0C);
        c.drawBorder(px, py, pw, ph, crateTone(k.id()));
        Ui.text(c, Ui.heading(k.title()), px + 8, py + 6, 0.95f, crateTone(k.id()), false);
        Ui.text(c, Text.literal("WHAT'S INSIDE  ·  odds per opening"), px + 8, py + 19, 0.55f, Ui.MUTED, false);
        int y = py + 30;
        for (String line : k.loot()) {
            String[] f = line.split("\\|", 4);
            if (f.length < 4) continue;
            int rar = Math.max(0, Math.min(5, Integer.parseInt(f[0])));
            int permille = Integer.parseInt(f[1]);
            int col = RARITY[rar];
            if (rar == 5) {
                // Mythic lines pulse.
                int pa = (int) (60 + 50 * Math.sin(t * 4));
                c.fill(px + 3, y - 1, px + pw - 3, y + rowH - 3, (pa << 24) | 0xE02A2A);
            }
            c.fill(px + 4, y, px + 7, y + rowH - 4, col);
            c.fill(px + 10, y, px + 26, y + 16, 0xFF1A1612);
            c.drawBorder(px + 10, y, 16, 16, col);
            c.drawItem(iconStack(f[2]), px + 10, y);
            Ui.text(c, Text.literal(fit(f[3], pw - 110, 0.62f)), px + 30, y + 1, 0.62f, col, false);
            Ui.text(c, Text.literal(RARITY_NAME[rar]), px + 30, y + 9, 0.5f, Ui.MUTED, false);
            // The chance, and a bar for it (a log scale, so the tiny ones still show).
            String pct = permille >= 10 ? (permille / 10.0 + "%").replace(".0%", "%") : (permille / 10.0) + "%";
            Ui.text(c, Text.literal(pct), px + pw - 8 - Ui.font().getWidth(pct) * 0.65f, y + 1, 0.65f, Ui.CREAM, false);
            int bw = (int) (60 * Math.log10(1 + permille) / 3);
            c.fill(px + pw - 70, y + 11, px + pw - 10, y + 13, 0x40FFFFFF);
            c.fill(px + pw - 70, y + 11, px + pw - 70 + bw, y + 13, col);
            // Cosmetic lines: a strip of what could come out, in their colours.
            if (f[2].endsWith("amethyst_shard") || f[2].endsWith("nether_star")) {
                boolean myth = rar == 5;
                int sx = px + 30 + (int) (Ui.font().getWidth(RARITY_NAME[rar]) * 0.5f) + 6, n = 0;
                for (CosmeticFx.Category cat : CosmeticFx.CATEGORIES) {
                    for (CosmeticFx.Entry e : cat.entries()) {
                        if (e.tier() == 0 || (e.tier() == 4) != myth || n >= 12) continue;
                        c.fill(sx + n * 6, y + 9, sx + n * 6 + 3, y + 13, 0xFF000000 | e.color());
                        c.fill(sx + n * 6 + 3, y + 9, sx + n * 6 + 5, y + 13, 0xFF000000 | e.color2());
                        n++;
                    }
                }
            }
            y += rowH;
        }
        c.getMatrices().pop();
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
        int rar = Math.max(0, Math.min(5, reveal.rarity()));
        boolean mythic = rar == 5;
        int col = RARITY[rar];
        c.fill(0, 0, width, height, 0xC0000000);
        if (mythic && ms > 900 && ms < 1300) {
            // Mythic: a red flash, and the whole thing jolts.
            int fa = (int) (200 * (1 - (ms - 900) / 400f));
            c.fill(0, 0, width, height, (fa << 24) | 0xE02A2A);
            cx += (int) (Math.sin(ms * 0.2) * 5);
            cy += (int) (Math.cos(ms * 0.27) * 4);
        }
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
        for (int i = 0; i < (mythic ? 28 : 16); i++) {
            double a = i * Math.PI / (mythic ? 14 : 8) + t * (mythic ? -1.1 : 0.6);
            int len = (int) ((60 + rar * 18) * k);
            for (int d = 16; d < len; d += 3) {
                int alpha = (int) (160 * (1 - d / (float) len));
                int px = cx + (int) (Math.cos(a) * d), py = cy + (int) (Math.sin(a) * d);
                c.fill(px - 1, py - 1, px + 1, py + 1, (alpha << 24) | (col & 0xFFFFFF));
            }
        }
        // Burst of sparks outward.
        for (int i = 0; i < (mythic ? 90 : 40); i++) {
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
            client.player.playSound(rar >= 4 ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.ENTITY_PLAYER_LEVELUP, 0.7f, 1f);
            if (mythic) {
                client.player.playSound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 0.8f);
                client.player.playSound(SoundEvents.ENTITY_WITHER_SPAWN, 0.4f, 1.4f);
            }
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
