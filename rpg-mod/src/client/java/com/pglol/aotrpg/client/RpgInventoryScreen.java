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
 * Survival inventory in the AoT style: the same slots on cloth panels with recessed slots, a header
 * tab, the crest behind your level, numbered hotbar, and a character panel with a large viewer.
 */
public class RpgInventoryScreen extends InventoryScreen {
    private static final int PANEL_W = 132;
    private float mouseXf, mouseYf;
    private AotButton satchelButton, skillsButton;

    public RpgInventoryScreen(PlayerEntity player) {
        super(player);
    }

    /** The recipe book pushes the inventory right; the side panel only fits when it is closed. */
    private boolean panel() {
        return x == (width - backgroundWidth) / 2 && x - PANEL_W - 4 >= 2;
    }

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
        boolean side = panel();
        int left = side ? x - PANEL_W - 4 : x;
        int right = x + backgroundWidth;

        // Header tab across both panels
        Net.Sync p = ClientState.profile;
        int tabW = 150, tabX = (left + right) / 2 - tabW / 2;
        Ui.panel(c, tabX, y - 17, tabW, 15);
        Text head = Ui.heading(p != null ? p.name() + "  ·  Inventory" : "Inventory");
        c.drawTextWithShadow(textRenderer, head, tabX + (tabW - textRenderer.getWidth(head)) / 2, y - 13, Ui.GOLD);

        Ui.panel(c, x, y, backgroundWidth, backgroundHeight);
        c.fillGradient(x + 1, y + 1, x + backgroundWidth - 1, y + 40, 0x18E0B96A, 0x00000000);

        // Equipment rail next to the armour slots
        c.fill(x + 4, y + 8, x + 5, y + 78, 0x80B8955A);
        for (Slot s : handler.slots) Ui.slot(c, x + s.x - 1, y + s.y - 1);
        // Section rule above the main inventory, clear of the gear column
        c.fill(x + 8, y + 81, x + backgroundWidth - 8, y + 82, 0x607A6139);
        // Hotbar numbers
        for (int i = 0; i < 9; i++) {
            c.drawText(textRenderer, Text.literal(String.valueOf(i + 1)), x + 8 + i * 18 + 1, y + 142 + 1, 0x80E0B96A, false);
        }
        // Crafting arrow
        c.drawTextWithShadow(textRenderer, Text.literal("→"), x + 138, y + 32, Ui.TRIM);

        // Crest behind the level, where the small vanilla preview used to be.
        int cx = x + 51;
        Ui.crest(c, cx - 24, y + 10, 48, 0.35f);
        if (p != null) {
            Ui.text(c, Ui.title(String.valueOf(p.level())), cx, y + 24, 2f, Ui.GOLD, true);
            Ui.text(c, Ui.heading("Level"), cx, y + 44, 0.8f, Ui.MUTED, true);
            Ui.text(c, Text.literal(p.disciplineEnum().title), cx, y + 58, 0.9f, Ui.disciplineColor(p.discipline()), true);
        }

        if (side) drawCharacterPanel(c, x - PANEL_W - 4, y, p);
    }

    private void drawCharacterPanel(DrawContext c, int lx, int y, Net.Sync p) {
        int h = backgroundHeight;
        Ui.panel(c, lx, y, PANEL_W, h);
        // Player viewer: y+6 .. y+86
        c.fillGradient(lx + 4, y + 4, lx + PANEL_W - 4, y + 86, 0x30B8955A, 0x08000000);
        Ui.crest(c, lx + PANEL_W / 2 - 30, y + 12, 60, 0.12f);
        InventoryScreen.drawEntity(c, lx + 4, y + 6, lx + PANEL_W - 4, y + 86, 32, 0.0625f, mouseXf, mouseYf, client.player);
        Ui.divider(c, lx + 8, y + 89, PANEL_W - 16);

        var pl = client.player;
        int ty = y + 94;
        row(c, lx, ty, "Health", Math.round(pl.getHealth()) + " / " + Math.round(pl.getMaxHealth()), Ui.HP);
        row(c, lx, ty + 10, "Armor", String.valueOf(pl.getArmor()), Ui.CREAM);
        row(c, lx, ty + 20, "Damage", String.format(Locale.ROOT, "%.1f", pl.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE)), Ui.CREAM);
        row(c, lx, ty + 30, "Speed", Math.round(pl.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) / 0.1 * 100) + "%", Ui.CREAM);
        row(c, lx, ty + 40, "Food", pl.getHungerManager().getFoodLevel() + " / 20",
            pl.getHungerManager().getFoodLevel() <= 6 ? Ui.RED : Ui.FOOD);
    }

    private void row(DrawContext c, int lx, int y, String k, String v, int color) {
        c.drawTextWithShadow(textRenderer, Text.literal(k), lx + 8, y, Ui.MUTED);
        c.drawTextWithShadow(textRenderer, Text.literal(v), lx + PANEL_W - 8 - textRenderer.getWidth(v), y, color);
    }

    @Override
    protected void drawForeground(DrawContext c, int mouseX, int mouseY) {
        c.drawTextWithShadow(textRenderer, Ui.heading("Crafting"), 97, 7, Ui.GOLD);
    }

    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top, int button) {
        // Clicks on the character panel must not drop the held item.
        if (panel() && mouseX >= left - PANEL_W - 4 && mouseX < left && mouseY >= top && mouseY < top + backgroundHeight) return false;
        return super.isClickOutsideBounds(mouseX, mouseY, left, top, button);
    }
}
