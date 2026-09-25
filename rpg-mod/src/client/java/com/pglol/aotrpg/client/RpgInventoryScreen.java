package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Survival inventory in the AoT style: the same slots on cloth panels with recessed slots, a header
 * tab, the crest behind your level, the loadout hotbar, and a character panel with viewer and loadout.
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
        // What an empty loadout slot is for.
        for (int i = 0; i < 9; i++) {
            int sx = x + 8 + i * 18, sy = y + 142;
            if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16 && handler.getCursorStack().isEmpty()
                && client.player.getInventory().main.get(i).isEmpty()) {
                com.pglol.aotrpg.Loadout.Kind k = com.pglol.aotrpg.Loadout.SLOTS[i];
                c.drawTooltip(textRenderer, java.util.List.of(Text.literal(k.title).withColor(LoadoutUi.color(k)),
                    Text.literal(k.hint).withColor(Ui.MUTED)), mouseX, mouseY);
            }
        }
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
        // Loadout hotbar: each slot framed in its purpose colour, a ghost of what belongs there,
        // and the combat wing / support wing pointing in at the heal slot.
        for (int i = 0; i < 9; i++) {
            com.pglol.aotrpg.Loadout.Kind k = com.pglol.aotrpg.Loadout.SLOTS[i];
            int col = LoadoutUi.color(k);
            int sx = x + 7 + i * 18, sy = y + 141;
            c.drawBorder(sx, sy, 18, 18, (i == com.pglol.aotrpg.Loadout.HEAL_SLOT ? 0xFF : 0x90) << 24 | (col & 0xFFFFFF));
            c.fill(sx + 1, sy + 16, sx + 17, sy + 17, 0xC0000000 | (col & 0xFFFFFF));
            if (client.player.getInventory().main.get(i).isEmpty()) LoadoutUi.drawGhost(c, LoadoutUi.ghost(k), sx + 1, sy + 1);
        }
        int hx = x + 7 + com.pglol.aotrpg.Loadout.HEAL_SLOT * 18;
        c.drawBorder(hx - 1, y + 140, 20, 20, Ui.TRIM);
        LoadoutUi.chevron(c, hx - 5, y + 144, 12, 1, 0xC0C0463A);
        LoadoutUi.chevron(c, hx + 24, y + 144, 12, -1, 0xC08F8A7A);
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
        // Player viewer: y+4 .. y+66
        c.fillGradient(lx + 4, y + 4, lx + PANEL_W - 4, y + 66, 0x30B8955A, 0x08000000);
        Ui.crest(c, lx + PANEL_W / 2 - 24, y + 10, 48, 0.12f);
        InventoryScreen.drawEntity(c, lx + 4, y + 4, lx + PANEL_W - 4, y + 66, 26, 0.0625f, mouseXf, mouseYf, client.player);

        drawLoadout(c, lx, y + 68);

        var pl = client.player;
        int ty = y + 106;
        row(c, lx, ty, "Health", Math.round(pl.getHealth()) + " / " + Math.round(pl.getMaxHealth()), Ui.HP);
        row(c, lx, ty + 9, "Damage", String.format(Locale.ROOT, "%.1f", pl.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE)), Ui.CREAM);
        row(c, lx, ty + 18, "Speed", Math.round(pl.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) / 0.1 * 100) + "%", Ui.CREAM);
        row(c, lx, ty + 27, "Food", pl.getHungerManager().getFoodLevel() + " / 20",
            pl.getHungerManager().getFoodLevel() <= 6 ? Ui.RED : Ui.FOOD);
    }

    /** Melee pair (main + sheath/off hand) | armour shield | ranged pair (ranged + sidearm). */
    private void drawLoadout(DrawContext c, int lx, int y) {
        var inv = client.player.getInventory();
        Ui.divider(c, lx + 8, y, PANEL_W - 16);
        Ui.text(c, Ui.heading("Melee"), lx + 25, y + 4, 0.8f, LoadoutUi.color(com.pglol.aotrpg.Loadout.Kind.MELEE), true);
        Ui.text(c, Ui.heading("Ranged"), lx + PANEL_W - 25, y + 4, 0.8f, LoadoutUi.color(com.pglol.aotrpg.Loadout.Kind.RANGED), true);
        // Melee: slot 1 and its partner (the drawn/sheathed twin grip or the off hand).
        ItemStack main = inv.main.get(0), off = client.player.getOffHandStack();
        Net.SheathState st = ClientState.sheaths.get(client.player.getUuid());
        ItemStack partner = off;
        String state = "";
        if (com.pglol.aotrpg.Loadout.isGrip(off)) state = "Drawn";
        else if (st != null && st.count() > 0 && !st.item().isEmpty()) {
            if (off.isEmpty()) partner = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(st.item())).getDefaultStack();
            state = "Sheathed";
        }
        loadoutSlot(c, lx + 6, y + 13, main, com.pglol.aotrpg.Loadout.Kind.MELEE);
        loadoutSlot(c, lx + 26, y + 13, partner, com.pglol.aotrpg.Loadout.Kind.SIDEARM);
        if (!state.isEmpty()) Ui.text(c, net.minecraft.text.Text.literal(state), lx + 25, y + 33, 0.6f,
            state.equals("Drawn") ? Ui.RED : Ui.GOLD, true);
        loadoutSlot(c, lx + PANEL_W - 44, y + 13, inv.main.get(1), com.pglol.aotrpg.Loadout.Kind.RANGED);
        loadoutSlot(c, lx + PANEL_W - 24, y + 13, inv.main.get(2), com.pglol.aotrpg.Loadout.Kind.SIDEARM);
        // Shield with the armour value.
        int sx = lx + PANEL_W / 2 - 14, sy = y + 5;
        c.fill(sx, sy, sx + 28, sy + 22, 0xF0151A16);
        LoadoutUi.chevronDown(c, sx, sy + 22, 28, 0xF0151A16);
        c.drawBorder(sx, sy, 28, 22, Ui.TRIM);
        Ui.text(c, Ui.heading("AR"), sx + 14, sy + 3, 0.6f, Ui.MUTED, true);
        Ui.text(c, Ui.title(String.valueOf(client.player.getArmor())), sx + 14, sy + 11, 1.1f, Ui.CREAM, true);
    }

    private void loadoutSlot(DrawContext c, int x, int y, ItemStack s, com.pglol.aotrpg.Loadout.Kind k) {
        Ui.slot(c, x, y);
        c.drawBorder(x, y, 18, 18, 0x90000000 | (LoadoutUi.color(k) & 0xFFFFFF));
        if (s.isEmpty()) LoadoutUi.drawGhost(c, LoadoutUi.ghost(k), x + 1, y + 1);
        else {
            c.drawItem(s, x + 1, y + 1);
            c.drawItemInSlot(textRenderer, s, x + 1, y + 1);
        }
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
