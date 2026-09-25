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

/** The character creator: Origin, Discipline, Attributes, Identity, Enlist. */
public class CreatorScreen extends Screen {
    public static final int POINTS = 5;
    private static final String[] STEPS = {"Origin", "Discipline", "Attributes", "Identity", "Enlist"};

    /** Choices survive re-opening the screen (e.g. after the server rejects a name). */
    static final class Draft {
        int step;
        Origin origin;
        Discipline discipline;
        final int[] stats = new int[Stat.values().length];
        String first = "", family = Names.rollFamily(null);

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
        if (this.error != null) d.step = 3;
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
            case 1 -> disciplineStep();
            case 2 -> statsStep();
            case 3 -> identityStep();
            default -> enlistStep();
        }

        int by = height - 28;
        if (d.step > 0) addDrawableChild(new AotButton(left, by, 90, 20, Ui.heading("← Back"), () -> go(d.step - 1)));
        if (d.step < 4) {
            AotButton next = new AotButton(left + w - 90, by, 90, 20, Ui.heading("Next →"), () -> go(d.step + 1));
            next.active = canAdvance();
            addDrawableChild(next);
        }
    }

    private boolean canAdvance() {
        return switch (d.step) {
            case 0 -> d.origin != null;
            case 1 -> d.discipline != null;
            case 3 -> Names.clean(d.first) != null && Names.clean(d.family) != null;
            default -> true;
        };
    }

    private void go(int step) {
        d.step = Math.max(0, Math.min(4, step));
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
            plus.active = left2 < POINTS;
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
        Ui.backdrop(c, width, height);
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
            case 1 -> drawDiscipline(c);
            case 2 -> drawStats(c);
            case 3 -> drawIdentity(c);
            default -> drawEnlist(c);
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
        int remaining = POINTS - d.spent();
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
        String sub = "Lv 1 " + (d.discipline == null ? "" : d.discipline.title);
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
        line(c, "Discipline", d.discipline == null ? "-" : d.discipline.title, x, pw, y + 12,
            d.discipline == null ? Ui.CREAM : Ui.disciplineColor(d.discipline.ordinal()));
        int i = 0;
        for (Stat s : Stat.values()) {
            int bonus = (d.origin != null && d.origin.bonusStat == s ? 1 : 0) + (d.origin == Origin.UNDERGROUND && s == Stat.STRENGTH ? 1 : 0);
            line(c, s.title, String.valueOf(d.stats[s.ordinal()] + bonus), x, pw, y + 30 + 11 * i++, Ui.CREAM);
        }
        if (POINTS - d.spent() > 0) line(c, "Unspent points", String.valueOf(POINTS - d.spent()), x, pw, y + 30 + 11 * i, Ui.MUTED);
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
