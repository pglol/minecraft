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

    @Override
    public void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = width, h = height;
        boolean hov = isHovered() && active;
        int bg = !active ? 0xB0101010 : selected ? 0xE8342A18 : hov ? 0xE8252C24 : 0xDC141914;
        c.fill(x, y, x + w, y + h, bg);
        c.drawBorder(x, y, w, h, !active ? 0xFF34322C : selected ? Ui.GOLD : hov ? Ui.TRIM : Ui.BORDER);
        if (selected || hov) c.fill(x + 1, y + 1, x + 3, y + h - 1, accent);
        int col = !active ? Ui.DIM : (selected || hov) ? Ui.GOLD : Ui.CREAM;
        int tx = x + 8;
        if (icon != null) {
            c.drawItem(icon, x + 6, y + (h - 16) / 2);
            tx = x + 27;
        }
        if (icon == null && sub == null) {
            Ui.text(c, getMessage(), x + w / 2f, y + (h - 8 * textScale) / 2f + 1, textScale, col, true);
        } else if (sub == null) {
            c.drawTextWithShadow(Ui.font(), getMessage(), tx, y + (h - 8) / 2, col);
        } else {
            c.drawTextWithShadow(Ui.font(), getMessage(), tx, y + h / 2 - 9, col);
            c.drawTextWithShadow(Ui.font(), sub, tx, y + h / 2 + 1, Ui.MUTED);
        }
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder b) {
        appendDefaultNarrations(b);
    }
}
