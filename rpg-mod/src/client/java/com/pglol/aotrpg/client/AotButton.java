package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/** A dark, gold-trimmed button. Can carry an item icon and a second line. */
public class AotButton extends PressableWidget {
    private final Runnable action;
    public boolean selected;
    public ItemStack icon;
    public Text sub;
    public int accent = Ui.GOLD;
    public float textScale = 1f;

    public AotButton(int x, int y, int w, int h, Text message, Runnable action) {
        super(x, y, w, h, message);
        this.action = action;
    }

    public AotButton icon(ItemStack s) {
        icon = s;
        return this;
    }

    public AotButton sub(Text t) {
        sub = t;
        return this;
    }

    public AotButton selected(boolean b) {
        selected = b;
        return this;
    }

    @Override
    public void onPress() {
        action.run();
    }

    /** Something waiting behind this button: a pulsing "!" in its corner. */
    public boolean alert;

    @Override
    public void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
        body(c);
        if (!alert) return;
        int x = getX() + width - 9, y = getY() - 3;
        float pulse = 0.75f + 0.25f * (float) Math.sin(net.minecraft.util.Util.getMeasuringTimeMs() / 180.0);
        int a = (int) (255 * pulse);
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 200);
        c.fill(x, y + 1, x + 11, y + 10, (a << 24) | 0xD8342A);
        c.fill(x + 1, y, x + 10, y + 11, (a << 24) | 0xD8342A);
        c.drawBorder(x, y, 11, 11, 0xFF6A1410);
        Ui.text(c, Text.literal("!"), x + 5.5f, y + 2, 0.9f, 0xFFFFFFFF, true);
        c.getMatrices().pop();
    }

    private void body(DrawContext c) {
        int x = getX(), y = getY(), w = width, h = height;
        int mouseX = 0, mouseY = 0;
        boolean hov = isHovered() && active;
        int bg = !active ? 0xB0101010 : selected ? 0xE8342A18 : hov ? 0xE8252C24 : 0xDC141914;
        c.fill(x, y, x + w, y + h, bg);
        c.drawBorder(x, y, w, h, !active ? 0xFF34322C : selected ? Ui.GOLD : hov ? Ui.TRIM : Ui.BORDER);
        if (selected || hov) c.fill(x + 1, y + 1, x + 3, y + h - 1, accent);
        int col = !active ? Ui.DIM : (selected || hov) ? Ui.GOLD : Ui.CREAM;
        int tx = x + 8;
        if (icon != null) {
            boolean alone = sub == null && getMessage().getString().isEmpty();
            c.drawItem(icon, alone ? x + (w - 16) / 2 : x + 6, y + (h - 16) / 2);
            tx = x + 27;
        }
        if (icon == null && sub == null) {
            Ui.text(c, getMessage(), x + w / 2f, y + (h - 8 * textScale) / 2f + 1, textScale, col, true);
        } else if (sub == null) {
            // Icon-only buttons (no label) show just the icon, centred.
            if (getMessage().getString().isEmpty()) return;
            c.drawTextWithShadow(Ui.font(), fit(getMessage(), x + w - 6 - tx), tx, y + (h - 8) / 2, col);
        } else {
            // Long lines are trimmed with an ellipsis so nothing spills past the button.
            c.drawTextWithShadow(Ui.font(), fit(getMessage(), x + w - 6 - tx), tx, y + h / 2 - 9, col);
            c.drawTextWithShadow(Ui.font(), fit(sub, x + w - 6 - tx), tx, y + h / 2 + 1, Ui.MUTED);
        }
    }

    private static net.minecraft.text.OrderedText fit(Text t, int max) {
        var f = Ui.font();
        if (f.getWidth(t) <= max) return t.asOrderedText();
        var cut = f.trimToWidth(t, Math.max(0, max - f.getWidth("…")));
        return net.minecraft.text.OrderedText.concat(net.minecraft.util.Language.getInstance().reorder(cut),
            Text.literal("…").asOrderedText());
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder b) {
        appendDefaultNarrations(b);
    }
}
