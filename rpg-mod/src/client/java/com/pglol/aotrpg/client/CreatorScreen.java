package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Discipline;
import com.pglol.aotrpg.Names;
import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.Origin;
import com.pglol.aotrpg.Stat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * The character creator. Where you're from; then your Mark, drawn by hand and read for what it
 * says about you (the strength you start with); then the harness, the Cadet Corps' first test,
 * where how steadily you hang earns extra training; then your body, your name, and you enlist.
 * The barracks plays on behind it, and your cadet stands beside it the whole way.
 */
public class CreatorScreen extends Screen {
    public static final int POINTS = 5;
    private static final String[] STEPS = {"Origin", "Your Mark", "The Harness", "Training", "Name", "Enlist"};

    /** Choices survive re-opening the screen (e.g. after the server rejects a name). */
    static final class Draft {
        int step;
        Origin origin;
        Discipline discipline;
        final int[] stats = new int[Stat.values().length];
        String first = "", family = Names.rollFamily(null);
        /** The mark as drawn (strokes of points in 0..1), when it was read, and how strongly. */
        final java.util.List<java.util.List<float[]>> strokes = new java.util.ArrayList<>();
        long revealAt;
        int resonance;
        boolean chimed;
        /** The harness: best result so far (-1 untried, else bonus points 0-2), and the run in progress. */
        int bonus = -1;
        String verdict = "";
        int phase;
        double theta, omega, sumAbs, windA, windB;
        long startAt, lastAt;
        int samples;
        boolean flipped;

        int points() {
            return POINTS + Math.max(0, bonus);
        }

        int spent() {
            int s = 0;
            for (int v : stats) s += v;
            return s;
        }
    }

    static Draft draft;
    private final Draft d;
    private String error;
    private int left, top, w, h, listW;
    private TextFieldWidget firstField, familyField;

    public CreatorScreen(String error) {
        super(Text.literal("Create your character"));
        if (draft == null) draft = new Draft();
        d = draft;
        this.error = error == null || error.isEmpty() ? null : error;
        if (this.error != null) d.step = 4;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        w = Math.min(470, width - 20);
        left = (width - w) / 2;
        top = 58;
        h = height - top - 36;
        listW = Math.min(180, w / 2 - 10);

        switch (d.step) {
            case 0 -> originStep();
            case 1 -> markStep();
            case 2 -> harnessStep();
            case 3 -> statsStep();
            case 4 -> identityStep();
            default -> enlistStep();
        }

        int by = height - 28;
        if (d.step > 0) addDrawableChild(new AotButton(left, by, 90, 20, Ui.heading("← Back"), () -> go(d.step - 1)));
        if (d.step < 5) {
            AotButton next = new AotButton(left + w - 90, by, 90, 20, Ui.heading("Next →"), () -> go(d.step + 1));
            next.active = canAdvance();
            addDrawableChild(next);
        }
    }

    private boolean canAdvance() {
        return switch (d.step) {
            case 0 -> d.origin != null;
            case 1 -> d.discipline != null && Util.getMeasuringTimeMs() - d.revealAt > 1400;
            case 2 -> d.bonus >= 0 && d.phase != 1;
            case 4 -> Names.clean(d.first) != null && Names.clean(d.family) != null;
            default -> true;
        };
    }

    private void go(int step) {
        d.step = Math.max(0, Math.min(5, step));
        error = null;
        clearAndInit();
    }

    private void originStep() {
        int y = top + 4;
        int gap = Math.min(30, (h - 8) / Origin.values().length);
        for (Origin o : Origin.values()) {
            addDrawableChild(new AotButton(left, y, listW, gap - 4, Text.literal(o.title), () -> {
                d.origin = o;
                clearAndInit();
            }).icon(new ItemStack(o.icon)).sub(Text.literal(o.bonus.contains(".") ? o.bonus.substring(o.bonus.indexOf('.') + 2) : o.bonus))
                .selected(d.origin == o));
            y += gap;
        }
    }

    private void disciplineStep() {
        int y = top + 4;
        int gap = Math.min(34, (h - 8) / Discipline.values().length);
        for (Discipline dc : Discipline.values()) {
            AotButton b = new AotButton(left, y, listW, gap - 4, Text.literal(dc.title), () -> {
                d.discipline = dc;
                clearAndInit();
            }).icon(new ItemStack(dc.icon)).sub(Text.literal(dc.blurb)).selected(d.discipline == dc);
            b.accent = Ui.disciplineColor(dc.ordinal());
            addDrawableChild(b);
            y += gap;
        }
    }

    // ---------------------------------------------------------------- the mark

    private int canvasSize() {
        return Math.max(80, Math.min(h - 34, w / 2 - 10));
    }

    private void markStep() {
        int cs = canvasSize();
        addDrawableChild(new AotButton(left, top + cs + 6, cs / 2 - 2, 20, Ui.heading("Clear"), () -> {
            d.strokes.clear();
            d.discipline = null;
            d.revealAt = 0;
            clearAndInit();
        }));
        AotButton read = addDrawableChild(new AotButton(left + cs / 2 + 2, top + cs + 6, cs / 2 - 2, 20, Ui.heading("Read my mark"), this::readMark));
        int pts = 0;
        for (var st : d.strokes) pts += st.size();
        read.active = pts >= 6 && d.discipline == null;
    }

    private void readMark() {
        MarkReader.Reading r = MarkReader.read(d.strokes);
        d.discipline = r.mark();
        d.resonance = r.resonance();
        d.revealAt = Util.getMeasuringTimeMs();
        d.chimed = false;
        if (client != null && client.player != null) {
            client.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 0.8f);
            client.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.7f);
        }
        clearAndInit();
    }

    private boolean inCanvas(double mx, double my) {
        int cs = canvasSize();
        return mx >= left && mx < left + cs && my >= top && my < top + cs;
    }

    private boolean drawing;

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (d.step == 1 && button == 0 && inCanvas(mx, my) && d.strokes.size() < 10) {
            // Drawing again after a reading starts a fresh one.
            if (d.discipline != null) {
                d.discipline = null;
                d.revealAt = 0;
                d.strokes.clear();
            }
            int cs = canvasSize();
            java.util.List<float[]> st = new java.util.ArrayList<>();
            st.add(new float[] {(float) (mx - left) / cs, (float) (my - top) / cs});
            d.strokes.add(st);
            drawing = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (drawing && d.step == 1 && !d.strokes.isEmpty()) {
            int cs = canvasSize();
            float x = (float) Math.max(0, Math.min(1, (mx - left) / cs)), y = (float) Math.max(0, Math.min(1, (my - top) / cs));
            var st = d.strokes.get(d.strokes.size() - 1);
            float[] last = st.get(st.size() - 1);
            if (Math.hypot(x - last[0], y - last[1]) > 0.006 && st.size() < 600) st.add(new float[] {x, y});
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (drawing) {
            drawing = false;
            clearAndInit();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    /** A thick line, square by square. */
    private static void seg(DrawContext c, double x0, double y0, double x1, double y1, int t, int col) {
        int n = (int) Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        for (int i = 0; i <= n; i++) {
            int x = (int) (x0 + (x1 - x0) * i / n), y = (int) (y0 + (y1 - y0) * i / n);
            c.fill(x - t / 2, y - t / 2, x - t / 2 + t, y - t / 2 + t, col);
        }
    }

    private void drawMark(DrawContext c, int mouseX, int mouseY) {
        int cs = canvasSize();
        long now = Util.getMeasuringTimeMs();
        float since = d.discipline == null ? -1 : (now - d.revealAt) / 1000f;
        int col = d.discipline == null ? 0xE0B96A : d.discipline.markColor();
        // The page: old paper, a faint ring to draw inside.
        c.fill(left - 2, top - 2, left + cs + 2, top + cs + 2, 0xFF3A2A1A);
        c.fill(left, top, left + cs, top + cs, 0xFFD6C49C);
        c.fillGradient(left, top, left + cs, top + cs, 0x00000000, 0x30502A10);
        for (int k = 0; k < 64; k++) {
            double a = k / 64.0 * Math.PI * 2;
            int rx = (int) (left + cs / 2.0 + Math.cos(a) * cs * 0.42), ry = (int) (top + cs / 2.0 + Math.sin(a) * cs * 0.42);
            c.fill(rx, ry, rx + 1, ry + 1, 0x30402010);
        }
        // The ink, and once read, the light running through it in the mark's colour.
        int total = 0;
        for (var st : d.strokes) total += st.size();
        int lit = since < 0 ? 0 : (int) (total * Math.min(1, since / 1.1f));
        int k = 0;
        float pulse = (float) (0.75 + 0.25 * Math.sin(now / 180.0));
        for (var st : d.strokes) {
            for (int i = 0; i < st.size(); i++, k++) {
                float[] a = st.get(Math.max(0, i - 1)), b = st.get(i);
                double x0 = left + a[0] * cs, y0 = top + a[1] * cs, x1 = left + b[0] * cs, y1 = top + b[1] * cs;
                if (k < lit) {
                    int glow = ((int) (110 * pulse) << 24) | col;
                    seg(c, x0, y0, x1, y1, 7, glow);
                    seg(c, x0, y0, x1, y1, 3, 0xFF000000 | col);
                } else {
                    seg(c, x0, y0, x1, y1, 3, 0xFF22160C);
                }
            }
        }
        if (since >= 1.1f) {
            // The reading lands: a flash and a ring out from the middle.
            float t = since - 1.1f;
            if (!d.chimed && client != null && client.player != null) {
                d.chimed = true;
                client.player.playSound(net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
                client.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.6f);
                clearAndInit();
            }
            if (t < 0.25f) c.fill(left, top, left + cs, top + cs, ((int) (200 * (1 - t / 0.25f)) << 24) | 0xFFFFFF);
            float r = Math.min(1, t / 0.6f) * cs * 0.7f;
            int ra = (int) (200 * Math.max(0, 1 - t / 0.6f));
            if (ra > 4) for (int i = 0; i < 96; i++) {
                double a = i / 96.0 * Math.PI * 2;
                int x = (int) (left + cs / 2.0 + Math.cos(a) * r), y = (int) (top + cs / 2.0 + Math.sin(a) * r);
                c.fill(x - 1, y - 1, x + 2, y + 2, (ra << 24) | col);
            }
        }
        // Beside it: what to do, then what it says.
        int ix = left + cs + 14, iw = left + w - ix;
        Ui.panel(c, ix, top, iw, h);
        if (d.discipline == null || since < 1.1f) {
            Ui.text(c, Ui.title("DRAW YOUR MARK"), ix + iw / 2f, top + 14, 1.3f, Ui.GOLD, true);
            Ui.wrapped(c, Text.literal("Every cadet leaves a mark on their first day. Draw anything that feels like yours: a crest, a shape, "
                + "a scrawl. It will be read."), ix + 10, top + 38, iw - 20, Ui.CREAM);
            if (since >= 0) Ui.text(c, Ui.heading("Reading..."), ix + iw / 2f, top + h / 2f, 1.2f, 0xFF000000 | col, true);
            return;
        }
        Discipline m = d.discipline;
        float in = Math.min(1, (since - 1.1f) / 0.3f);
        float sc = 2.0f - 0.5f * in;
        Ui.text(c, Ui.title(m.markName().toUpperCase()), ix + iw / 2f, top + 14, sc, ((int) (255 * in) << 24) | col, true);
        Ui.text(c, Text.literal("Resonance " + d.resonance + "%"), ix + iw / 2f, top + 40, 0.85f, 0xFFE8E0D0, true);
        Ui.divider(c, ix + 10, top + 54, iw - 20);
        int ty = Ui.wrapped(c, Text.literal(m.markLore()), ix + 10, top + 62, iw - 20, Ui.CREAM);
        c.drawTextWithShadow(textRenderer, Ui.heading("Your strength"), ix + 10, ty + 10, Ui.GOLD);
        ty = Ui.wrapped(c, Text.literal(m.perks), ix + 10, ty + 22, iw - 20, 0xFF8FCB6A);
        c.drawTextWithShadow(textRenderer, Ui.heading("Issued"), ix + 10, ty + 8, Ui.GOLD);
        Ui.wrapped(c, Text.literal("Cadet uniform, training blade, rations. " + gear(m) + "."), ix + 10, ty + 20, iw - 20, Ui.CREAM);
    }

    // ---------------------------------------------------------------- the harness

    private void harnessStep() {
        AotButton go = addDrawableChild(new AotButton(width / 2 - 70, top + h - 30, 140, 22,
            Ui.heading(d.phase == 0 && d.bonus < 0 ? "Hang me up" : d.phase == 1 ? "Hold steady..." : "Again"), this::startHarness));
        go.active = d.phase != 1;
    }

    private void startHarness() {
        d.phase = 1;
        var r = new java.util.Random();
        d.theta = (r.nextBoolean() ? 1 : -1) * (0.05 + r.nextDouble() * 0.06);
        d.omega = 0;
        d.sumAbs = 0;
        d.samples = 0;
        d.flipped = false;
        d.windA = r.nextDouble() * 6;
        d.windB = r.nextDouble() * 6;
        d.startAt = d.lastAt = Util.getMeasuringTimeMs();
        if (client != null && client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_CHAIN_PLACE, 1f, 0.8f);
        clearAndInit();
    }

    private static final float RUN = 8f;

    private void stepHarness(int mouseX) {
        long now = Util.getMeasuringTimeMs();
        double dt = Math.min(0.05, (now - d.lastAt) / 1000.0);
        d.lastAt = now;
        double t = (now - d.startAt) / 1000.0;
        if (d.flipped) {
            // Over you go.
            d.theta += (Math.signum(d.theta) * Math.PI - d.theta) * Math.min(1, dt * 6);
            if (t > 1.2 + d.samples * 0) finishHarness();
            return;
        }
        // Lean against it: the mouse (or A / D) pushes the other way to whichever side you move it.
        int cx = left + w / 2;
        double u = Math.max(-1, Math.min(1, (mouseX - cx) / 70.0));
        long win = client.getWindow().getHandle();
        if (net.minecraft.client.util.InputUtil.isKeyPressed(win, org.lwjgl.glfw.GLFW.GLFW_KEY_A)
            || net.minecraft.client.util.InputUtil.isKeyPressed(win, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT)) u = -1;
        if (net.minecraft.client.util.InputUtil.isKeyPressed(win, org.lwjgl.glfw.GLFW.GLFW_KEY_D)
            || net.minecraft.client.util.InputUtil.isKeyPressed(win, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT)) u = 1;
        double harder = 0.6 + 0.8 * Math.min(1, t / RUN);
        double wind = (1.1 * Math.sin(t * 1.3 + d.windA) + 0.8 * Math.sin(t * 2.7 + d.windB)) * harder;
        double acc = 2.8 * Math.sin(d.theta) + wind - 1.5 * d.omega + 6.0 * u;
        d.omega += acc * dt;
        d.theta += d.omega * dt;
        d.sumAbs += Math.abs(d.theta);
        d.samples++;
        if (Math.abs(d.theta) > 1.25) {
            d.flipped = true;
            d.startAt = now - (long) (RUN * 1000);
            if (client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_HURT, 0.6f, 1.4f);
            return;
        }
        if (t >= RUN) finishHarness();
    }

    private void finishHarness() {
        d.phase = 2;
        int got;
        if (d.flipped) {
            got = 0;
            d.verdict = "UPSIDE DOWN";
        } else {
            double avg = d.sumAbs / Math.max(1, d.samples);
            got = avg < 0.16 ? 2 : avg < 0.36 ? 1 : 0;
            d.verdict = got == 2 ? "FLAWLESS" : got == 1 ? "STEADY" : "WOBBLY";
        }
        d.bonus = Math.max(d.bonus, got);
        if (client != null && client.player != null) client.player.playSound(got == 2 ? net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
            : net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.7f, got == 2 ? 1f : 0.8f);
        clearAndInit();
    }

    private void drawHarness(DrawContext c, int mouseX, int mouseY) {
        if (d.phase == 1) stepHarness(mouseX);
        Ui.panel(c, left, top, w, h);
        int cx = left + w / 2, cy = top + h / 2 - 4;
        // Instructor Shadis.
        String line = d.phase == 1 ? (d.flipped ? "WHAT ARE YOU DOING?! GET UPRIGHT!" : "Hold it. HOLD IT.")
            : d.phase == 2 ? switch (d.verdict) {
                case "FLAWLESS" -> "...Hmph. Not bad, cadet.";
                case "STEADY" -> "Adequate. Barely.";
                case "UPSIDE DOWN" -> "Just like Jaeger. Again!";
                default -> "You call that balance?";
            } : "Cadet! Show me you can keep yourself upright in the gear!";
        Ui.text(c, Ui.heading("Instructor Shadis"), cx, top + 8, 0.8f, Ui.MUTED, true);
        Ui.text(c, Text.literal("\"" + line + "\""), cx, top + 20, 1f, Ui.CREAM, true);
        // The rig: two posts, a crossbar, ropes to your belt.
        int px = 70, postTop = cy - 56;
        c.fill(cx - px - 3, postTop, cx - px + 3, top + h - 36, 0xFF5A3E22);
        c.fill(cx + px - 3, postTop, cx + px + 3, top + h - 36, 0xFF5A3E22);
        c.fill(cx - px - 6, postTop - 4, cx + px + 6, postTop + 2, 0xFF6B4A2A);
        seg(c, cx - px, postTop + 4, cx - 5, cy, 1, 0xFF2A1E14);
        seg(c, cx + px, postTop + 4, cx + 5, cy, 1, 0xFF2A1E14);
        // You, hanging from the waist, turned by how far you've tipped.
        var m = c.getMatrices();
        m.push();
        m.translate(cx, cy, 0);
        m.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotation((float) d.theta));
        c.fill(-7, -28, 7, 0, 0xFF5A4030);
        c.fill(-7, -24, 7, -22, 0xFF22160C);
        c.fill(-7, -8, 7, -6, 0xFF22160C);
        c.fill(-6, 0, -1, 24, 0xFFE8E0D0);
        c.fill(1, 0, 6, 24, 0xFFE8E0D0);
        c.fill(-6, 20, -1, 26, 0xFF3A2A1A);
        c.fill(1, 20, 6, 26, 0xFF3A2A1A);
        c.fill(-11, -26, -7, -6, 0xFF5A4030);
        c.fill(7, -26, 11, -6, 0xFF5A4030);
        if (client != null && client.player != null) {
            net.minecraft.client.gui.PlayerSkinDrawer.draw(c, client.getSkinProvider().getSkinTextures(client.player.getGameProfile()), -7, -43, 14);
        }
        m.pop();
        // The balance meter and the clock.
        int mw = 160, mx = cx - mw / 2, my = top + h - 56;
        c.fill(mx, my, mx + mw, my + 5, 0x60000000);
        c.fill(cx - 12, my, cx + 12, my + 5, 0x607FD06A);
        int kx = (int) (cx + Math.max(-1, Math.min(1, d.theta / 1.25)) * mw / 2);
        c.fill(kx - 2, my - 2, kx + 2, my + 7, Math.abs(d.theta) < 0.36 ? 0xFF7FD06A : 0xFFE0463A);
        if (d.phase == 1 && !d.flipped) {
            float left2 = Math.max(0, RUN - (Util.getMeasuringTimeMs() - d.startAt) / 1000f);
            Ui.text(c, Ui.title(String.format(java.util.Locale.ROOT, "%.1f", left2)), cx, my - 18, 1.1f, Ui.GOLD, true);
        }
        if (d.phase == 2) {
            int col = d.verdict.equals("FLAWLESS") ? 0xFF7FD06A : d.verdict.equals("STEADY") ? 0xFFE0B96A : 0xFFE0463A;
            Ui.text(c, Ui.title(d.verdict), left + 60, cy - 10, 1.5f, col, true);
            Ui.text(c, Text.literal("+" + Math.max(0, d.bonus) + " training"), left + w - 60, cy - 10, 1.1f, Ui.GOLD, true);
        }
    }

    private int statRowY(int i) {
        return top + 34 + i * Math.min(34, (h - 44) / 4);
    }

    private void statsStep() {
        int left2 = d.spent();
        for (Stat s : Stat.values()) {
            int y = statRowY(s.ordinal());
            int bx = left + w - 58;
            AotButton minus = new AotButton(bx, y + 3, 22, 20, Text.literal("−"), () -> {
                d.stats[s.ordinal()]--;
                clearAndInit();
            });
            minus.active = d.stats[s.ordinal()] > 0;
            AotButton plus = new AotButton(bx + 26, y + 3, 22, 20, Text.literal("+"), () -> {
                d.stats[s.ordinal()]++;
                clearAndInit();
            });
            plus.active = left2 < d.points();
            addDrawableChild(minus);
            addDrawableChild(plus);
        }
    }

    private void identityStep() {
        int fx = left + 10, fw = listW - 20;
        firstField = new TextFieldWidget(textRenderer, fx, top + 38, fw, 18, Text.literal("First name"));
        firstField.setMaxLength(14);
        firstField.setTextPredicate(s -> s.matches("[A-Za-z'-]*"));
        firstField.setText(d.first);
        firstField.setChangedListener(s -> {
            d.first = s;
            refreshNext();
        });
        firstField.setPlaceholder(Text.literal("e.g. Anna").withColor(Ui.DIM));
        addDrawableChild(firstField);

        AotButton user = new AotButton(fx, top + 60, fw, 16, Text.literal("Use my username"), () -> {
            String n = Names.clean(MinecraftClient.getInstance().getSession().getUsername().replaceAll("[^A-Za-z'-]", ""));
            if (n != null) {
                d.first = n;
                firstField.setText(n);
            }
        });
        addDrawableChild(user);

        familyField = new TextFieldWidget(textRenderer, fx, top + 102, fw - 24, 18, Text.literal("Family name"));
        familyField.setMaxLength(14);
        familyField.setTextPredicate(s -> s.matches("[A-Za-z'-]*"));
        familyField.setText(d.family);
        familyField.setChangedListener(s -> {
            d.family = s;
            refreshNext();
        });
        addDrawableChild(familyField);
        AotButton roll = new AotButton(fx + fw - 20, top + 101, 20, 20, Text.literal("⚄"), () -> {
            d.family = Names.rollFamily(d.family);
            familyField.setText(d.family);
        });
        addDrawableChild(roll);
        setInitialFocus(firstField);
    }

    private void refreshNext() {
        for (var c : children()) {
            if (c instanceof AotButton b && b.getMessage().getString().startsWith("Next")) b.active = canAdvance();
        }
    }

    private void enlistStep() {
        AotButton enlist = new AotButton(width / 2 - 90, top + h - 44, 180, 30, Ui.title("ENLIST"), () -> {
            ClientPlayNetworking.send(new Net.Create(d.origin.ordinal(), d.discipline.ordinal(), d.stats.clone(),
                Names.clean(d.first), Names.clean(d.family)));
            close();
        });
        enlist.textScale = 1.6f;
        enlist.active = d.origin != null && d.discipline != null && canNames();
        addDrawableChild(enlist);
    }

    private boolean canNames() {
        return Names.clean(d.first) != null && Names.clean(d.family) != null;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        if (com.pglol.aotrpg.client.story.CutscenePlayer.active()) {
            // The barracks shows through: dark at the edges and behind the panel, clear around it.
            c.fillGradient(0, 0, width, height, 0x28000000, 0x70000000);
            c.fill(left - 10, top - 6, left + w + 10, height - 32, 0xB00C100D);
            c.fillGradient(0, 0, width, 56, 0xC0000000, 0x00000000);
        } else {
            Ui.backdrop(c, width, height);
        }
        Ui.text(c, Ui.title("ATTACK ON TITAN"), width / 2f, 10, 2.2f, Ui.GOLD, true);
        Ui.text(c, Text.literal("Year 845  ·  Create your character"), width / 2f, 34, 1f, Ui.MUTED, true);

        // Step tracker
        int sw = w / STEPS.length;
        for (int i = 0; i < STEPS.length; i++) {
            int sx = left + i * sw;
            int col = i == d.step ? Ui.GOLD : i < d.step ? Ui.CREAM : Ui.DIM;
            Text t = Ui.heading((i + 1) + "  " + STEPS[i]);
            c.drawTextWithShadow(textRenderer, t, sx + (sw - textRenderer.getWidth(t)) / 2, 46, col);
            if (i == d.step) c.fill(sx + 8, 56, sx + sw - 8, 57, Ui.GOLD);
            else c.fill(sx + 8, 56, sx + sw - 8, 57, 0x40FFFFFF);
        }

        switch (d.step) {
            case 0 -> drawOrigin(c);
            case 1 -> drawMark(c, mouseX, mouseY);
            case 2 -> drawHarness(c, mouseX, mouseY);
            case 3 -> drawStats(c);
            case 4 -> drawIdentity(c);
            default -> drawEnlist(c);
        }
        // Your cadet, beside it all (not over the drawing pad or the harness).
        if (d.step != 1 && d.step != 2 && client != null && client.player != null) {
            int mx0 = left + w + 12;
            if (width - mx0 > 80) {
                net.minecraft.client.gui.screen.ingame.InventoryScreen.drawEntity(c, mx0, top + 10, width - 12, top + h - 10,
                    (int) Math.min(90, h / 2.4f), 0.0625f, mouseX, mouseY, client.player);
            }
        }
    }

    private int infoX() {
        return left + listW + 10;
    }

    private int infoW() {
        return w - listW - 10;
    }

    private void drawOrigin(DrawContext c) {
        int x = infoX(), y = top, iw = infoW();
        Ui.panel(c, x, y, iw, h);
        if (d.origin == null) {
            Ui.text(c, Ui.heading("Where did you grow up?"), x + iw / 2f, y + 14, 1.2f, Ui.GOLD, true);
            Ui.wrapped(c, Text.literal("For a hundred years humanity has lived behind three great walls: Maria, Rose and Sina. "
                + "Beyond them, titans roam. Choose the district you call home. It is where your story begins."),
                x + 10, y + 36, iw - 20, Ui.CREAM);
            return;
        }
        Ui.item(c, new ItemStack(d.origin.icon), x + 10, y + 10, 2f);
        Ui.text(c, Ui.title(d.origin.title), x + 48, y + 14, 1.5f, Ui.GOLD, false);
        Ui.divider(c, x + 10, y + 46, iw - 20);
        int ty = Ui.wrapped(c, Text.literal(d.origin.blurb), x + 10, y + 56, iw - 20, Ui.CREAM);
        ty = Ui.wrapped(c, Text.literal(d.origin.bonus), x + 10, ty + 8, iw - 20, 0xFF8FCB6A);
        Ui.wrapped(c, Text.literal("You will begin your journey in " + d.origin.title + "."), x + 10, ty + 8, iw - 20, Ui.MUTED);
    }

    private static String gear(Discipline dc) {
        return switch (dc) {
            case SCOUT -> "Flare gun and 6 flares";
            case VANGUARD -> "A spare blade";
            case GUARDIAN -> "Garrison shield";
            case MARKSMAN -> "Hunting bow and 48 arrows";
            case MEDIC -> "3 field rations and 6 medicinal herbs";
        };
    }

    private void drawDiscipline(DrawContext c) {
        int x = infoX(), y = top, iw = infoW();
        Ui.panel(c, x, y, iw, h);
        if (d.discipline == null) {
            Ui.text(c, Ui.heading("How do you fight?"), x + iw / 2f, y + 14, 1.2f, Ui.GOLD, true);
            Ui.wrapped(c, Text.literal("Every cadet learns the blade, but each has a strength. Your discipline shapes your "
                + "bonuses and the gear you are issued."), x + 10, y + 36, iw - 20, Ui.CREAM);
            return;
        }
        int col = Ui.disciplineColor(d.discipline.ordinal());
        Ui.item(c, new ItemStack(d.discipline.icon), x + 10, y + 10, 2f);
        Ui.text(c, Ui.title(d.discipline.title), x + 48, y + 14, 1.5f, col, false);
        Ui.divider(c, x + 10, y + 46, iw - 20);
        int ty = Ui.wrapped(c, Text.literal(d.discipline.blurb), x + 10, y + 56, iw - 20, Ui.CREAM);
        c.drawTextWithShadow(textRenderer, Ui.heading("Perks"), x + 10, ty + 8, Ui.GOLD);
        ty = Ui.wrapped(c, Text.literal(d.discipline.perks), x + 10, ty + 20, iw - 20, 0xFF8FCB6A);
        c.drawTextWithShadow(textRenderer, Ui.heading("Issued gear"), x + 10, ty + 8, Ui.GOLD);
        Ui.wrapped(c, Text.literal("Cadet uniform, training blade, rations. " + gear(d.discipline) + "."),
            x + 10, ty + 20, iw - 20, Ui.CREAM);
    }

    private void drawStats(DrawContext c) {
        Ui.panel(c, left, top, w, h);
        int remaining = d.points() - d.spent();
        Ui.text(c, Ui.heading("Train your body"), left + 10, top + 8, 1.2f, Ui.GOLD, false);
        Text pts = Ui.title(remaining + " point" + (remaining == 1 ? "" : "s") + " left");
        c.drawTextWithShadow(textRenderer, pts, left + w - 10 - textRenderer.getWidth(pts), top + 10, remaining > 0 ? Ui.GOLD : Ui.MUTED);
        Ui.divider(c, left + 10, top + 26, w - 20);
        for (Stat s : Stat.values()) {
            int y = statRowY(s.ordinal());
            int bonus = d.origin != null && d.origin.bonusStat == s ? 1 : 0;
            if (d.origin == Origin.UNDERGROUND && s == Stat.STRENGTH) bonus++;
            int val = d.stats[s.ordinal()] + bonus;
            c.drawItem(new ItemStack(s.icon), left + 12, y + 5);
            c.drawTextWithShadow(textRenderer, Ui.heading(s.title), left + 34, y + 4, Ui.CREAM);
            c.drawTextWithShadow(textRenderer, Text.literal(s.effect + (bonus > 0 ? "   (+" + bonus + " origin)" : "")),
                left + 34, y + 15, Ui.MUTED);
            // pips
            int px = left + w - 150;
            for (int i = 0; i < 8; i++) {
                int col = i < bonus ? 0xFF8FCB6A : i < val ? Ui.GOLD : 0x40FFFFFF;
                c.fill(px + i * 10, y + 9, px + i * 10 + 7, y + 16, col);
            }
            Ui.text(c, Ui.title(String.valueOf(val)), px - 14, y + 7, 1.3f, Ui.CREAM, true);
        }
    }

    private void drawIdentity(DrawContext c) {
        Ui.panel(c, left, top, listW, h);
        c.drawTextWithShadow(textRenderer, Ui.heading("First name"), left + 10, top + 26, Ui.GOLD);
        c.drawTextWithShadow(textRenderer, Ui.heading("Family name"), left + 10, top + 90, Ui.GOLD);
        c.drawTextWithShadow(textRenderer, Text.literal("Roll ⚄ or type your own"), left + 10, top + 124, Ui.MUTED);
        Ui.text(c, Ui.heading("Who are you?"), left + 10, top + 8, 1.2f, Ui.CREAM, false);

        int x = infoX(), iw = infoW();
        Ui.panel(c, x, top, iw, h);
        c.drawTextWithShadow(textRenderer, Ui.heading("How others will see you"), x + 10, top + 10, Ui.GOLD);
        // Mock of the floating name tag.
        String name = (d.first.isEmpty() ? "?" : d.first) + " " + (d.family.isEmpty() ? "?" : d.family);
        Text n = Text.literal(name).styled(s -> s.withBold(true));
        String sub = "Lv 1 " + (d.discipline == null ? "" : d.discipline.markName());
        int tw = Math.max(textRenderer.getWidth(n), textRenderer.getWidth(sub)) + 12;
        int cx = x + iw / 2, ty = top + 40;
        c.fill(cx - tw / 2, ty, cx + tw / 2, ty + 24, 0x60000000);
        c.drawCenteredTextWithShadow(textRenderer, n, cx, ty + 3, 0xFFFFFFFF);
        c.drawCenteredTextWithShadow(textRenderer, Text.literal(sub), cx, ty + 13,
            d.discipline == null ? Ui.GOLD : Ui.disciplineColor(d.discipline.ordinal()));
        Ui.wrapped(c, Text.literal("Your name floats above you for nearby players. One word each, 2 to 14 letters."),
            x + 10, ty + 40, iw - 20, Ui.MUTED);
        if (error != null) Ui.wrapped(c, Text.literal(error), x + 10, top + h - 24, iw - 20, Ui.RED);
    }

    private void drawEnlist(DrawContext c) {
        int pw = Math.min(300, w);
        int x = (width - pw) / 2;
        Ui.panel(c, x, top, pw, h);
        Ui.text(c, Ui.heading("Cadet"), width / 2f, top + 10, 1f, Ui.MUTED, true);
        Ui.text(c, Ui.title(Names.clean(d.first) + " " + Names.clean(d.family)), width / 2f, top + 22, 1.6f, Ui.GOLD, true);
        Ui.divider(c, x + 20, top + 44, pw - 40);
        int y = top + 54;
        line(c, "Origin", d.origin == null ? "-" : d.origin.title, x, pw, y, Ui.CREAM);
        line(c, "Mark", d.discipline == null ? "-" : d.discipline.markName(), x, pw, y + 12,
            d.discipline == null ? Ui.CREAM : 0xFF000000 | d.discipline.markColor());
        int i = 0;
        for (Stat s : Stat.values()) {
            int bonus = (d.origin != null && d.origin.bonusStat == s ? 1 : 0) + (d.origin == Origin.UNDERGROUND && s == Stat.STRENGTH ? 1 : 0);
            line(c, s.title, String.valueOf(d.stats[s.ordinal()] + bonus), x, pw, y + 30 + 11 * i++, Ui.CREAM);
        }
        if (d.points() - d.spent() > 0) line(c, "Unspent points", String.valueOf(d.points() - d.spent()), x, pw, y + 30 + 11 * i, Ui.MUTED);
        float pulse = (float) (0.6 + 0.4 * Math.sin(Util.getMeasuringTimeMs() / 400.0));
        int a = (int) (255 * pulse);
        Ui.text(c, Ui.heading("Dedicate your heart!"), width / 2f, top + h - 60, 1f, (a << 24) | 0xE0B96A, true);
        if (error != null) Ui.text(c, Text.literal(error), width / 2f, top + h - 10, 1f, Ui.RED, true);
    }

    private void line(DrawContext c, String k, String v, int x, int pw, int y, int col) {
        c.drawTextWithShadow(textRenderer, Text.literal(k), x + 30, y, Ui.MUTED);
        c.drawTextWithShadow(textRenderer, Text.literal(v), x + pw - 30 - textRenderer.getWidth(v), y, col);
    }
}
