package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Satchel;
import com.pglol.aotrpg.SatchelHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

/** The satchel: story items and supplies, safe from death. */
public class SatchelScreen extends HandledScreen<SatchelHandler> {
    public SatchelScreen(SatchelHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        backgroundWidth = 176;
        backgroundHeight = 186;
        playerInventoryTitleY = SatchelHandler.INV_Y - 11;
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        drawMouseoverTooltip(c, mouseX, mouseY);
    }

    @Override
    protected void drawBackground(DrawContext c, float delta, int mouseX, int mouseY) {
        Ui.panel(c, x, y, backgroundWidth, backgroundHeight);
        c.fillGradient(x + 1, y + 1, x + backgroundWidth - 1, y + 30, 0x20E0B96A, 0x00000000);
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot s = handler.slots.get(i);
            int sx = x + s.x - 1, sy = y + s.y - 1;
            boolean bag = i < Satchel.SIZE;
            Ui.slot(c, sx, sy);
            if (bag && Satchel.isStory(s.getStack())) c.drawBorder(sx, sy, 18, 18, Ui.GOLD);
        }
    }

    @Override
    protected void drawForeground(DrawContext c, int mouseX, int mouseY) {
        c.drawTextWithShadow(textRenderer, Ui.heading("Satchel"), 8, 6, Ui.GOLD);
        String note = "never lost on death";
        c.drawTextWithShadow(textRenderer, Text.literal(note), backgroundWidth - 8 - textRenderer.getWidth(note), 6, Ui.MUTED);
        c.drawTextWithShadow(textRenderer, Ui.heading("Inventory"), 8, playerInventoryTitleY, Ui.GOLD);
    }
}
