package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Survival inventory in the AoT style: the same slots, a dark gold-trimmed frame, and a
 * character panel on the left with a large player viewer and live stats.
 */
public class RpgInventoryScreen extends InventoryScreen {
    private static final int PANEL_W = 132;
    private float mouseXf, mouseYf;

    public RpgInventoryScreen(PlayerEntity player) {
        super(player);
    }

    /** The recipe book pushes the inventory right; the side panel only fits when it is closed. */
    private boolean panel() {
        return x == (width - backgroundWidth) / 2 && x - PANEL_W - 4 >= 2;
    }

    private AotButton satchelButton, skillsButton;

    @Override
    protected void init() {
        super.init();
        satchelButton = addDrawableChild(new AotButton(0, 0, 60, 14, Text.literal("Satchel [B]"),
            () -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.OpenSatchel())));
        skillsButton = addDrawableChild(new AotButton(0, 0, 60, 14, Text.literal("Skills [K]"),
            () -> client.setScreen(new CharacterScreen(0))));
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        mouseXf = mouseX;
        mouseYf = mouseY;
        // Follow the panel (the recipe book can move the inventory).
        boolean show = panel();
        int lx = x - PANEL_W - 4;
        satchelButton.visible = skillsButton.visible = show;
        satchelButton.setPosition(lx + 4, y + backgroundHeight - 19);
        skillsButton.setPosition(lx + PANEL_W - 64, y + backgroundHeight - 19);
        Net.Sync p = ClientState.profile;
        skillsButton.selected = p != null && p.points() + p.skillPoints() > 0;
        super.render(c, mouseX, mouseY, delta);
    }

    @Override
    protected void drawBackground(DrawContext c, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y;
        Ui.panel(c, x, y, backgroundWidth, backgroundHeight);
        c.fillGradient(x + 1, y + 1, x + backgroundWidth - 1, y + 40, 0x18E0B96A, 0x00000000);

        for (Slot s : handler.slots) {
            int sx = x + s.x - 1, sy = y + s.y - 1;
            c.fill(sx, sy, sx + 18, sy + 18, 0xFF161A16);
            c.drawBorder(sx, sy, 18, 18, 0xFF3E3527);
            c.fill(sx + 1, sy + 1, sx + 17, sy + 2, 0x14FFFFFF);
        }
        // crafting arrow between the 2x2 grid and the result
        c.drawTextWithShadow(textRenderer, Text.literal("→"), x + 138, y + 32, Ui.TRIM);
        Ui.divider(c, x + 8, y + 79, backgroundWidth - 16);

        // Where the small vanilla preview used to be: the character crest.
        Net.Sync p = ClientState.profile;
        int cx = x + 51;
        if (p != null) {
            Ui.text(c, Ui.heading("Level"), cx, y + 12, 0.9f, Ui.MUTED, true);
            Ui.text(c, Ui.title(String.valueOf(p.level())), cx, y + 24, 2f, Ui.GOLD, true);
            Ui.text(c, Text.literal(p.disciplineEnum().title), cx, y + 50, 0.9f, Ui.disciplineColor(p.discipline()), true);
        }

        if (panel()) drawCharacterPanel(c, x - PANEL_W - 4, y, p);
    }

    private void drawCharacterPanel(DrawContext c, int lx, int y, Net.Sync p) {
        int h = backgroundHeight;
        Ui.panel(c, lx, y, PANEL_W, h);
        if (p != null) {
            Text name = Ui.heading(p.name());
            c.drawTextWithShadow(textRenderer, name, lx + (PANEL_W - textRenderer.getWidth(name)) / 2, y + 5, Ui.CREAM);
        }
        // Player viewer: y+16 .. y+86
        c.fillGradient(lx + 4, y + 16, lx + PANEL_W - 4, y + 86, 0x30B8955A, 0x10000000);
        InventoryScreen.drawEntity(c, lx + 4, y + 16, lx + PANEL_W - 4, y + 86, 30, 0.0625f, mouseXf, mouseYf, client.player);
        Ui.divider(c, lx + 8, y + 89, PANEL_W - 16);

        // Stats: five rows, y+94 .. y+143
        var pl = client.player;
        int ty = y + 94;
        row(c, lx, ty, "Health", Math.round(pl.getHealth()) + " / " + Math.round(pl.getMaxHealth()), Ui.HP);
        row(c, lx, ty + 10, "Armor", String.valueOf(pl.getArmor()), Ui.CREAM);
        row(c, lx, ty + 20, "Damage", String.format(Locale.ROOT, "%.1f", pl.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE)), Ui.CREAM);
        row(c, lx, ty + 30, "Speed", Math.round(pl.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) / 0.1 * 100) + "%", Ui.CREAM);
        row(c, lx, ty + 40, "Food", pl.getHungerManager().getFoodLevel() + " / 20",
            pl.getHungerManager().getFoodLevel() <= 6 ? Ui.RED : Ui.FOOD);
        // Buttons sit at y+147 .. y+161 (see init)
    }

    private void row(DrawContext c, int lx, int y, String k, String v, int color) {
        c.drawTextWithShadow(textRenderer, Text.literal(k), lx + 8, y, Ui.MUTED);
        c.drawTextWithShadow(textRenderer, Text.literal(v), lx + PANEL_W - 8 - textRenderer.getWidth(v), y, color);
    }

    @Override
    protected void drawForeground(DrawContext c, int mouseX, int mouseY) {
        c.drawTextWithShadow(textRenderer, Ui.heading("Crafting"), 97, 7, Ui.GOLD);
        c.drawTextWithShadow(textRenderer, Ui.heading("Inventory"), 8, 72, Ui.GOLD);
    }

    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top, int button) {
        // Clicks on the character panel must not drop the held item.
        if (panel() && mouseX >= left - PANEL_W - 4 && mouseX < left && mouseY >= top && mouseY < top + backgroundHeight) return false;
        return super.isClickOutsideBounds(mouseX, mouseY, left, top, button);
    }

}
